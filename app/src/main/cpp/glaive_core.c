#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <stdint.h>
#include <arm_neon.h>
#include <android/log.h>

#define LOG_TAG "GLAIVE_C"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Kernel struct for getdents64
struct linux_dirent64 {
    unsigned long long d_ino;
    long long d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
};

typedef enum {
    TYPE_UNKNOWN = 0,
    TYPE_DIR = 1,
    TYPE_IMG = 2,
    TYPE_VID = 3,
    TYPE_APK = 4,
    TYPE_DOC = 5,
    TYPE_FILE = 6
} FileType;

// Unused functions removed


// ==========================================
// SEARCH HELPERS
// ==========================================
static inline int has_glob_tokens(const char *pattern) {
    while (*pattern) {
        if (*pattern == '*' || *pattern == '?') return 1;
        pattern++;
    }
    return 0;
}

static inline unsigned char fold_ci(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return (unsigned char)(c + 32);
    return c;
}

int neon_contains(const char *haystack, const char *needle, size_t n_len) {
    size_t h_len = strlen(haystack);
    if (h_len < n_len) return 0;

    uint8_t first = (uint8_t)tolower(needle[0]);
    uint8x16_t v_first = vdupq_n_u8(first);
    uint8x16_t v_case = vdupq_n_u8(0x20);

    size_t i = 0;
    for (; i + 16 <= h_len; i += 16) {
        uint8x16_t block = vld1q_u8((const uint8_t*)(haystack + i));
        uint8x16_t block_lower = vorrq_u8(block, v_case);
        uint8x16_t eq = vceqq_u8(v_first, block_lower);
        uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
        if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
            for (int k = 0; k < 16; k++) {
                if (tolower(haystack[i + k]) == first) {
                    if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                }
            }
        }
    }

    for (; i < h_len; i++) {
        if (tolower(haystack[i]) == first) {
            if (strncasecmp(haystack + i, needle, n_len) == 0) return 1;
        }
    }
    return 0;
}

static int glob_match_ci(const char *text, const char *pattern) {
    const char *t = text;
    const char *p = pattern;
    const char *star = NULL;
    const char *match = NULL;

    while (*t) {
        unsigned char tc = fold_ci((unsigned char)*t);
        unsigned char pc = (unsigned char)*p;

        if (pc && (pc == '?' || fold_ci(pc) == tc)) {
            t++;
            p++;
            continue;
        }
        if (pc == '*') {
            star = ++p;
            match = t;
            continue;
        }
        if (star) {
            p = star;
            t = ++match;
            continue;
        }
        return 0;
    }
    while (*p == '*') p++;
    return *p == '\0';
}

static inline int matches_query(const char *name, const char *query, size_t qlen, int glob_mode) {
    return glob_mode ? glob_match_ci(name, query) : neon_contains(name, query, qlen);
}

// ==========================================
// FAST LISTING CORE
// ==========================================
// ==========================================
// TYPE DETECTION (Pure C - No ASM)
// ==========================================
static inline unsigned char fast_get_type(const char *name, int name_len) {
    if (name_len < 4) return TYPE_FILE;

    const char *ext = name + name_len - 1;
    // Scan backwards for dot
    int i = 0;
    while (i < 6 && ext > name) {
        if (*ext == '.') break;
        ext--;
        i++;
    }
    if (*ext != '.') return TYPE_FILE;
    
    // ext now points to dot.
    // Simple hash or switch on extension characters
    // We can use a simplified check for known extensions
    
    char e1 = tolower(ext[1]);
    char e2 = tolower(ext[2]);
    char e3 = tolower(ext[3]);
    char e4 = (i >= 4) ? tolower(ext[4]) : 0;

    if (e1 == 'p') {
        if (e2 == 'n' && e3 == 'g') return TYPE_IMG; // png
        if (e2 == 'd' && e3 == 'f') return TYPE_DOC; // pdf
        if (e2 == 'p' && e3 == 't') return TYPE_DOC; // ppt, pptx
    }
    if (e1 == 'j') {
        if (e2 == 'p' && e3 == 'g') return TYPE_IMG; // jpg
        if (e2 == 'p' && e3 == 'e' && e4 == 'g') return TYPE_IMG; // jpeg
    }
    if (e1 == 'm') {
        if (e2 == 'p' && e3 == '4') return TYPE_VID; // mp4
        if (e2 == 'k' && e3 == 'v') return TYPE_VID; // mkv
        if (e2 == 'o' && e3 == 'v') return TYPE_VID; // mov
    }
    if (e1 == 'a') {
        if (e2 == 'p' && e3 == 'k') return TYPE_APK; // apk
        if (e2 == 'v' && e3 == 'i') return TYPE_VID; // avi
    }
    if (e1 == 'g') {
        if (e2 == 'i' && e3 == 'f') return TYPE_IMG; // gif
    }
    if (e1 == 'w') {
        if (e2 == 'e' && e3 == 'b') {
            if (e4 == 'p') return TYPE_IMG; // webp
            if (e4 == 'm') return TYPE_VID; // webm
        }
    }
    if (e1 == 'd') {
        if (e2 == 'o' && e3 == 'c') return TYPE_DOC; // doc, docx
    }
    if (e1 == 'x') {
        if (e2 == 'l' && e3 == 's') return TYPE_DOC; // xls, xlsx
    }
    if (e1 == 't') {
        if (e2 == 'x' && e3 == 't') return TYPE_DOC; // txt
    }
    if (e1 == '3') {
        if (e2 == 'g' && e3 == 'p') return TYPE_VID; // 3gp
    }
    if (e1 == 'b') {
        if (e2 == 'm' && e3 == 'p') return TYPE_IMG; // bmp
    }

    return TYPE_FILE;
}

// ==========================================
// SORTING
// ==========================================
typedef struct {
    char* name;
    int name_len;
    unsigned char type;
    int64_t size;
    int64_t time;
} GlaiveEntry;

typedef enum {
    SORT_NAME = 0,
    SORT_DATE = 1,
    SORT_SIZE = 2,
    SORT_TYPE = 3
} SortMode;

static int g_sort_mode = SORT_NAME;
static int g_sort_asc = 1;

int compare_entries(const void* a, const void* b) {
    GlaiveEntry* ea = (GlaiveEntry*)a;
    GlaiveEntry* eb = (GlaiveEntry*)b;

    // Always Directories First
    if (ea->type == TYPE_DIR && eb->type != TYPE_DIR) return -1;
    if (ea->type != TYPE_DIR && eb->type == TYPE_DIR) return 1;

    int result = 0;
    switch (g_sort_mode) {
        case SORT_NAME:
            result = strcasecmp(ea->name, eb->name);
            break;
        case SORT_SIZE:
            if (ea->size < eb->size) result = -1;
            else if (ea->size > eb->size) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        case SORT_DATE:
            if (ea->time < eb->time) result = -1;
            else if (ea->time > eb->time) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        case SORT_TYPE:
            if (ea->type < eb->type) result = -1;
            else if (ea->type > eb->type) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        default:
            result = strcasecmp(ea->name, eb->name);
    }

    return g_sort_asc ? result : -result;
}

JNIEXPORT jint JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeFillBuffer(JNIEnv *env, jobject clazz, jstring jPath, jobject jBuffer, jint capacity, jint sortMode, jboolean asc, jint filterMask) {
    if (capacity <= 0) return 0;

    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    unsigned char *buffer = (*env)->GetDirectBufferAddress(env, jBuffer);
    if (!buffer) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -2;
    }

    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -1;
    }

    // Temporary storage for sorting
    // We'll allocate a large chunk on heap for pointers/structs
    // Assuming max 10k files for now, or realloc if needed.
    // For "unholy speed", we can use a fixed large buffer if memory allows, or realloc.
    // Let's start with 4096 entries and realloc.
    size_t max_entries = 4096;
    size_t count = 0;
    GlaiveEntry* entries = (GlaiveEntry*)malloc(max_entries * sizeof(GlaiveEntry));
    if (!entries) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -3;
    }

    char kbuf[4096];
    struct linux_dirent64 *d;
    struct stat st;
    int nread;

    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            if (d->d_name[0] == '.') continue;

            int name_len = 0;
            while (d->d_name[name_len] && name_len < 255) name_len++;

            unsigned char type = TYPE_UNKNOWN;
            int64_t size = 0;
            int64_t time = 0;

            // Determine type first to filter early
            if (d->d_type == DT_DIR) {
                type = TYPE_DIR;
            } else if (d->d_type == DT_REG) {
                type = fast_get_type(d->d_name, name_len);
            } else {
                 // Fallback stat for unknown types
                 if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                    if (S_ISDIR(st.st_mode)) type = TYPE_DIR;
                    else type = fast_get_type(d->d_name, name_len);
                 } else {
                    type = TYPE_FILE;
                 }
            }
            
            if (type == TYPE_UNKNOWN) type = fast_get_type(d->d_name, name_len);
            if (type == TYPE_UNKNOWN) type = TYPE_FILE;

            // FILTERING
            // If filterMask is set (not 0), and it's NOT a directory, check if bit is set.
            // We assume filterMask bits correspond to TYPE_* values.
            // Actually, usually mask is: 1<<TYPE_IMG | 1<<TYPE_VID etc.
            // If filterMask is empty (0), show all.
            // Always show directories.
            if (filterMask != 0 && type != TYPE_DIR) {
                if (!((1 << type) & filterMask)) {
                    continue; // Skip
                }
            }

            // Get stats if needed (for sorting or display)
            // We only stat if we need size/date OR if we haven't stat-ed yet.
            // For now, we always stat for correctness of display.
            if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                size = st.st_size;
                time = st.st_mtime;
            }

            // Add to list
            if (count >= max_entries) {
                max_entries *= 2;
                GlaiveEntry* new_entries = (GlaiveEntry*)realloc(entries, max_entries * sizeof(GlaiveEntry));
                if (!new_entries) break; // OOM
                entries = new_entries;
            }

            entries[count].name = strdup(d->d_name); // We must copy name
            entries[count].name_len = name_len;
            entries[count].type = type;
            entries[count].size = size;
            entries[count].time = time;
            count++;
        }
    }
    close(fd);

    // SORT
    g_sort_mode = sortMode;
    g_sort_asc = asc;
    qsort(entries, count, sizeof(GlaiveEntry), compare_entries);

    // SERIALIZE
    unsigned char *head = buffer;
    unsigned char *end = buffer + capacity;
    
    for (size_t i = 0; i < count; i++) {
        if (head + 2 + entries[i].name_len + 16 > end) break;

        *head++ = entries[i].type;
        *head++ = (unsigned char)entries[i].name_len;
        memcpy(head, entries[i].name, entries[i].name_len);
        head += entries[i].name_len;
        memcpy(head, &entries[i].size, sizeof(int64_t));
        head += sizeof(int64_t);
        memcpy(head, &entries[i].time, sizeof(int64_t));
        head += sizeof(int64_t);

        free(entries[i].name);
    }
    free(entries);

    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return (jint)(head - buffer);
}

// ==========================================
// SEARCH PIPELINE
// ==========================================
void recursive_scan(char* path_buf, size_t current_len, size_t base_len, const char* query, size_t qlen, int glob_mode, 
                   unsigned char** head_ptr, unsigned char* end, int filterMask) {
    if (*head_ptr >= end) return;

    DIR* dir = opendir(path_buf);
    if (!dir) return;

    struct dirent* d;
    // No large stack allocation here!

    while ((d = readdir(dir)) != NULL) {
        if (*head_ptr >= end) break;
        if (d->d_name[0] == '.') continue;

        size_t name_len = strlen(d->d_name);
        
        if (d->d_type == DT_DIR) {
            if (strcmp(d->d_name, "Android") != 0) {
                // Check path length limit
                if (current_len + 1 + name_len < 4096) {
                    path_buf[current_len] = '/';
                    memcpy(path_buf + current_len + 1, d->d_name, name_len + 1); // +1 for null
                    
                    recursive_scan(path_buf, current_len + 1 + name_len, base_len, query, qlen, glob_mode, head_ptr, end, filterMask);
                    
                    path_buf[current_len] = 0; // Restore for next iteration
                }
            }
        } else {
            if (matches_query(d->d_name, query, qlen, glob_mode)) {
                
                // Filter check
                unsigned char type = fast_get_type(d->d_name, name_len);
                if (filterMask != 0) {
                    if (!((1 << type) & filterMask)) continue;
                }

                // We need to write the RELATIVE path so GlaiveCursor can reconstruct the full path.
                // Construct full path in path_buf temporarily
                if (current_len + 1 + name_len < 4096) {
                    path_buf[current_len] = '/';
                    memcpy(path_buf + current_len + 1, d->d_name, name_len + 1);
                    
                    // Relative path starts after base_len + 1 (for the slash)
                    // If base_len is length of "/storage/emulated/0", relative starts at index base_len + 1
                    char* rel_path = path_buf + base_len + 1;
                    size_t rel_len = (current_len + 1 + name_len) - (base_len + 1);
                    
                    // Cap len at 255 for the protocol (or maybe we should allow longer? 
                    // The protocol uses 1 byte for len, so 255 is hard limit for "name" field.
                    // If relative path is > 255, we might truncate. 
                    // Ideally we'd change protocol to support longer paths, but for now cap it.)
                    int proto_len = (rel_len > 255) ? 255 : (int)rel_len;
                    
                    // Check buffer space
                    if (*head_ptr + 2 + proto_len + 16 > end) {
                        path_buf[current_len] = 0; // Restore
                        break;
                    }

                    // unsigned char type = fast_get_type(d->d_name, name_len); // Use actual name for type
                    int64_t size = 0;
                    int64_t time = 0;

                    // SKIP STAT FOR SPEED
                    
                    *(*head_ptr)++ = type;
                    *(*head_ptr)++ = (unsigned char)proto_len;
                    memcpy(*head_ptr, rel_path, proto_len);
                    *head_ptr += proto_len;
                    memcpy(*head_ptr, &size, sizeof(int64_t));
                    *head_ptr += sizeof(int64_t);
                    memcpy(*head_ptr, &time, sizeof(int64_t));
                    *head_ptr += sizeof(int64_t);
                    
                    path_buf[current_len] = 0; // Restore
                }
            }
        }
    }
    closedir(dir);
}

JNIEXPORT jint JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeSearch(JNIEnv *env, jobject clazz, jstring jRoot, jstring jQuery, jobject jBuffer, jint capacity, jint filterMask) {
    if (capacity <= 0) return 0;

    const char *root = (*env)->GetStringUTFChars(env, jRoot, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jQuery, NULL);
    unsigned char *buffer = (*env)->GetDirectBufferAddress(env, jBuffer);

    if (!buffer) {
        (*env)->ReleaseStringUTFChars(env, jRoot, root);
        (*env)->ReleaseStringUTFChars(env, jQuery, query);
        return -2;
    }

    size_t qlen = strlen(query);
    int glob_mode = has_glob_tokens(query);

    unsigned char *head = buffer;
    unsigned char *end = buffer + capacity;

    // Use a single heap-allocated buffer for path construction
    // Actually, instructions said "Move the path buffer to the stack or a reused thread_local static buffer"
    // Stack 4096 is fine for this depth.
    char path_buf[4096];
    
    size_t root_len = strlen(root);
    if (root_len < 4096) {
        strcpy(path_buf, root);
        // Remove trailing slash if present
        if (root_len > 1 && path_buf[root_len - 1] == '/') {
            path_buf[root_len - 1] = 0;
            root_len--;
        }
        
        recursive_scan(path_buf, root_len, root_len, query, qlen, glob_mode, &head, end, filterMask);
    }

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    
    return (jint)(head - buffer);
}
