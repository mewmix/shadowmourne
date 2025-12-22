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
#include <limits.h>

int64_t calculate_dir_size_recursive(int parent_fd, const char *path);

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
    TYPE_FILE = 6,
    TYPE_AUDIO = 7
} FileType;

// ==========================================
// SEARCH CONTEXT
// ==========================================
typedef struct {
    const char* query;
    size_t qlen;
    int glob_mode;
    uint8_t first_char_lower;
    uint8x16_t v_first_lower;
    uint8x16_t v_first_upper;
    int filterMask;
    uint8_t show_hidden;
    uint8_t show_app_data;
} SearchContext;

int64_t calculate_dir_size_recursive(int parent_fd, const char *path) {
    int64_t total_size = 0;
    struct stat st;

    // Open the directory relative to the parent file descriptor
    int dir_fd = openat(parent_fd, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd == -1) {
        return 0;
    }

    DIR *dir = fdopendir(dir_fd);
    if (!dir) {
        close(dir_fd);
        return 0;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        if (fstatat(dir_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
            if (S_ISDIR(st.st_mode)) {
                total_size += calculate_dir_size_recursive(dir_fd, entry->d_name);
            } else {
                total_size += st.st_size;
            }
        }
    }

    closedir(dir);
    return total_size;
}


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

static int optimized_neon_contains(const char *haystack, int h_len, const SearchContext* ctx) {
    // Direct access to context to avoid setup overhead
    if (h_len < ctx->qlen) return 0;

    size_t n_len = ctx->qlen;
    uint8x16_t v_lower = ctx->v_first_lower;
    uint8x16_t v_upper = ctx->v_first_upper;
    const char* needle = ctx->query;
    uint8_t first = ctx->first_char_lower;

    size_t i = 0;
    // Main SIMD loop
    for (; i + 16 <= h_len; i += 16) {
        uint8x16_t block = vld1q_u8((const uint8_t*)(haystack + i));

        // Compare against lower and upper case first char
        uint8x16_t eq_l = vceqq_u8(block, v_lower);
        uint8x16_t eq_u = vceqq_u8(block, v_upper);
        uint8x16_t eq = vorrq_u8(eq_l, eq_u);

        // Fast check if any bit is set in the 128-bit vector
        uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
        if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
            for (int k = 0; k < 16; k++) {
                // Verify manually
                if (tolower(haystack[i + k]) == first) {
                    if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                }
            }
        }
    }

    // Cleanup loop
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

static inline int optimized_matches_query(const char *name, int name_len, const SearchContext* ctx) {
    return ctx->glob_mode ? glob_match_ci(name, ctx->query) : optimized_neon_contains(name, name_len, ctx);
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
        if (e2 == 'p' && e3 == '3') return TYPE_AUDIO; // mp3
        if (e2 == 'k' && e3 == 'v') return TYPE_VID; // mkv
        if (e2 == 'o' && e3 == 'v') return TYPE_VID; // mov
        if (e2 == '4' && e3 == 'a') return TYPE_AUDIO; // m4a
    }
    if (e1 == 'a') {
        if (e2 == 'p' && e3 == 'k') return TYPE_APK; // apk
        if (e2 == 'v' && e3 == 'i') return TYPE_VID; // avi
        if (e2 == 'a' && e3 == 'c') return TYPE_AUDIO; // aac
    }
    if (e1 == 'f') {
        if (e2 == 'l' && e3 == 'a' && e4 == 'c') return TYPE_AUDIO; // flac
    }
    if (e1 == 'g') {
        if (e2 == 'i' && e3 == 'f') return TYPE_IMG; // gif
    }
    if (e1 == 'o') {
        if (e2 == 'g' && e3 == 'g') return TYPE_AUDIO; // ogg
        if (e2 == 'p' && e3 == 'u' && e4 == 's') return TYPE_AUDIO; // opus
    }
    if (e1 == 'w') {
        if (e2 == 'a' && e3 == 'v') return TYPE_AUDIO; // wav
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
Java_com_mewmix_glaive_core_NativeCore_nativeFillBuffer(JNIEnv *env, jobject clazz, jstring jPath, jobject jBuffer, jint capacity, jint sortMode, jboolean asc, jint filterMask, jboolean showHidden) {
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
    size_t max_entries = 4096;
    size_t count = 0;
    GlaiveEntry* entries = (GlaiveEntry*)malloc(max_entries * sizeof(GlaiveEntry));
    if (!entries) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -3;
    }

    char kbuf[4096] __attribute__((aligned(8)));
    struct linux_dirent64 *d;
    struct stat st;
    int nread;

    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            if (d->d_name[0] == '.') {
                if (d->d_name[1] == '\0') continue;
                if (d->d_name[1] == '.' && d->d_name[2] == '\0') continue;
                if (!showHidden) continue;
            }

            int name_len = 0;
            while (d->d_name[name_len] && name_len < 255) name_len++;

            unsigned char type = TYPE_UNKNOWN;
            int64_t size = 0;
            int64_t time = 0;

            if (d->d_type == DT_DIR) {
                type = TYPE_DIR;
            } else if (d->d_type == DT_REG) {
                type = fast_get_type(d->d_name, name_len);
            } else {
                 if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                    if (S_ISDIR(st.st_mode)) type = TYPE_DIR;
                    else type = fast_get_type(d->d_name, name_len);
                 } else {
                    type = TYPE_FILE;
                 }
            }
            
            if (type == TYPE_UNKNOWN) type = fast_get_type(d->d_name, name_len);
            if (type == TYPE_UNKNOWN) type = TYPE_FILE;

            if (filterMask != 0 && type != TYPE_DIR) {
                if (!((1 << type) & filterMask)) {
                    continue; // Skip
                }
            }

            if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                size = st.st_size;
                time = st.st_mtime;
            }

            if (count >= max_entries) {
                max_entries *= 2;
                GlaiveEntry* new_entries = (GlaiveEntry*)realloc(entries, max_entries * sizeof(GlaiveEntry));
                if (!new_entries) break;
                entries = new_entries;
            }

            entries[count].name = strdup(d->d_name);
            entries[count].name_len = name_len;
            entries[count].type = type;
            entries[count].size = size;
            entries[count].time = time;
            count++;
        }
    }
    close(fd);

    g_sort_mode = sortMode;
    g_sort_asc = asc;
    qsort(entries, count, sizeof(GlaiveEntry), compare_entries);

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

JNIEXPORT jlong JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeCalculateDirectorySize(JNIEnv *env, jobject clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    if (path == NULL) {
        return 0;
    }
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return 0;
    }

    int64_t size = calculate_dir_size_recursive(fd, ".");

    close(fd);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return size;
}

// ==========================================
// SEARCH PIPELINE
// ==========================================
void recursive_scan_optimized(char* path_buf, size_t current_len, size_t base_len,
                              const SearchContext* ctx,
                              unsigned char** head_ptr, unsigned char* end) {
    if (*head_ptr >= end) return;

    int fd = open(path_buf, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) return;

    // Use a stack buffer for getdents to avoid malloc
    char kbuf[4096] __attribute__((aligned(8)));
    struct linux_dirent64 *d;
    int nread;

    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            if (*head_ptr >= end) break;

            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            // Skip hidden files/dots
            if (d->d_name[0] == '.') {
                if (d->d_name[1] == '\0') continue;
                if (d->d_name[1] == '.' && d->d_name[2] == '\0') continue;
                if (!ctx->show_hidden) continue;
            }

            int name_len = 0;
            while (d->d_name[name_len]) name_len++;

            // Handle d_type for correctness
            unsigned char type = DT_UNKNOWN;
            if (d->d_type == DT_DIR) {
                type = DT_DIR;
            } else if (d->d_type == DT_REG) {
                type = DT_REG;
            } else {
                // Fallback for unknown type
                 struct stat st;
                 if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                     if (S_ISDIR(st.st_mode)) type = DT_DIR;
                     else type = DT_REG;
                 }
            }

            if (type == DT_DIR) {
                if (ctx->show_app_data || strcmp(d->d_name, "Android") != 0) {
                    if (current_len + 1 + name_len < 4096) {
                        path_buf[current_len] = '/';
                        memcpy(path_buf + current_len + 1, d->d_name, name_len + 1);

                        recursive_scan_optimized(path_buf, current_len + 1 + name_len, base_len, ctx, head_ptr, end);

                        path_buf[current_len] = 0;
                    }
                }
            } else {
                // It is a file (or treated as one)
                if (optimized_matches_query(d->d_name, name_len, ctx)) {
                    
                    unsigned char g_type = fast_get_type(d->d_name, name_len);
                    if (ctx->filterMask != 0) {
                        if (!((1 << g_type) & ctx->filterMask)) continue;
                    }

                    if (current_len + 1 + name_len < 4096) {
                        path_buf[current_len] = '/';
                        memcpy(path_buf + current_len + 1, d->d_name, name_len + 1);

                        char* rel_path = path_buf + base_len + 1;
                        size_t rel_len = (current_len + 1 + name_len) - (base_len + 1);

                        int proto_len = (rel_len > 255) ? 255 : (int)rel_len;

                        if (*head_ptr + 2 + proto_len + 16 <= end) {
                            *(*head_ptr)++ = g_type;
                            *(*head_ptr)++ = (unsigned char)proto_len;
                            memcpy(*head_ptr, rel_path, proto_len);
                            *head_ptr += proto_len;

                            // Zero size/time for search results (lazy load or not needed)
                            memset(*head_ptr, 0, 16);
                            *head_ptr += 16;
                        }

                        path_buf[current_len] = 0;
                    }
                }
            }
        }
    }
    close(fd);
}

JNIEXPORT jint JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeSearch(JNIEnv *env, jobject clazz, jstring jRoot, jstring jQuery, jobject jBuffer, jint capacity, jint filterMask, jboolean showHidden, jboolean showAppData) {
    if (capacity <= 0) return 0;

    const char *root = (*env)->GetStringUTFChars(env, jRoot, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jQuery, NULL);
    unsigned char *buffer = (*env)->GetDirectBufferAddress(env, jBuffer);

    if (!buffer) {
        (*env)->ReleaseStringUTFChars(env, jRoot, root);
        (*env)->ReleaseStringUTFChars(env, jQuery, query);
        return -2;
    }

    // Init Context
    SearchContext ctx;
    ctx.query = query;
    ctx.qlen = strlen(query);
    ctx.glob_mode = has_glob_tokens(query);
    ctx.filterMask = filterMask;
    ctx.show_hidden = (uint8_t)showHidden;
    ctx.show_app_data = (uint8_t)showAppData;

    // Precompute SIMD constants
    ctx.first_char_lower = (uint8_t)tolower(query[0]);
    ctx.v_first_lower = vdupq_n_u8(ctx.first_char_lower);
    ctx.v_first_upper = vdupq_n_u8((uint8_t)toupper(query[0]));

    unsigned char *head = buffer;
    unsigned char *end = buffer + capacity;

    char path_buf[4096];
    
    size_t root_len = strlen(root);
    if (root_len < 4096) {
        strcpy(path_buf, root);
        if (root_len > 1 && path_buf[root_len - 1] == '/') {
            path_buf[root_len - 1] = 0;
            root_len--;
        }
        
        recursive_scan_optimized(path_buf, root_len, root_len, &ctx, &head, end);
    }

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    
    return (jint)(head - buffer);
}
