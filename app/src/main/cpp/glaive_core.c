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
#include <android/log.h>
#include <limits.h>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

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
    TYPE_AUDIO = 7,
    TYPE_ARCHIVE = 8,
    TYPE_CODE = 9
} FileType;

// ==========================================
// ARENA ALLOCATOR (Zero Garbage)
// ==========================================
typedef struct ArenaNode {
    char* memory;
    size_t capacity;
    size_t used;
    struct ArenaNode* next;
} ArenaNode;

typedef struct {
    ArenaNode* head;
    ArenaNode* current;
} Arena;

static void arena_init(Arena* a, size_t first_block_size) {
    a->head = (ArenaNode*)malloc(sizeof(ArenaNode));
    a->head->memory = (char*)malloc(first_block_size);
    a->head->capacity = first_block_size;
    a->head->used = 0;
    a->head->next = NULL;
    a->current = a->head;
}

static char* arena_alloc_str(Arena* a, const char* str, int len) {
    if (a->current->used + len + 1 > a->current->capacity) {
        size_t new_cap = a->current->capacity * 2;
        if (new_cap < len + 1) new_cap = len + 1 + 4096;

        ArenaNode* node = (ArenaNode*)malloc(sizeof(ArenaNode));
        node->memory = (char*)malloc(new_cap);
        node->capacity = new_cap;
        node->used = 0;
        node->next = NULL;

        a->current->next = node;
        a->current = node;
    }

    char* dest = a->current->memory + a->current->used;
    memcpy(dest, str, len);
    dest[len] = '\0';
    a->current->used += len + 1;
    return dest;
}

static void arena_destroy(Arena* a) {
    ArenaNode* node = a->head;
    while (node) {
        ArenaNode* next = node->next;
        free(node->memory);
        free(node);
        node = next;
    }
}

// ==========================================
// CONTEXT
// ==========================================
typedef struct {
    const char* query;
    size_t qlen;
    int glob_mode;
    uint8_t first_char_lower;
#if defined(__ARM_NEON)
    uint8x16_t v_first_lower;
    uint8x16_t v_first_upper;
#endif
    int filterMask;
    int showHidden;
    int showAppData;
} SearchContext;

// ==========================================
// UTILS
// ==========================================
static inline unsigned char fast_get_type(const char *name, int name_len) {
    if (name_len < 3) return TYPE_FILE;

    const char *ext = name + name_len - 1;
    int i = 0;
    while (i < 7 && ext > name) {
        if (*ext == '.') break;
        ext--;
        i++;
    }
    if (*ext != '.') return TYPE_FILE;
    
    char e1 = tolower(ext[1]);
    char e2 = (i >= 2) ? tolower(ext[2]) : 0;
    char e3 = (i >= 3) ? tolower(ext[3]) : 0;
    char e4 = (i >= 4) ? tolower(ext[4]) : 0;

    switch (e1) {
        case 'a':
            if (e2 == 'p' && e3 == 'k' && e4 == 0) return TYPE_APK;
            if (e2 == 'a' && e3 == 'c' && e4 == 0) return TYPE_AUDIO;
            if (e2 == 'm' && e3 == 'r' && e4 == 0) return TYPE_AUDIO;
            if (e2 == 'v' && e3 == 'i' && e4 == 0) return TYPE_VID;
            break;
        case 'b':
            if (e2 == 'z' && e3 == '2' && e4 == 0) return TYPE_ARCHIVE;
            if (e2 == 'a' && e3 == 't' && e4 == 0) return TYPE_CODE;
            if (e2 == 'm' && e3 == 'p' && e4 == 0) return TYPE_IMG;
            break;
        case 'c':
            if (e2 == 0) return TYPE_CODE;
            if (e2 == 'p' && e3 == 'p' && e4 == 0) return TYPE_CODE;
            if (e2 == 's' && e3 == 's' && e4 == 0) return TYPE_CODE;
            if (e2 == 's' && e3 == 'v' && e4 == 0) return TYPE_DOC;
            break;
        case 'd':
            if (e2 == 'o' && e3 == 'c') return TYPE_DOC;
            break;
        case 'f':
            if (e2 == 'l' && e3 == 'a' && e4 == 'c') return TYPE_AUDIO;
            break;
        case 'g':
            if (e2 == 'i' && e3 == 'f' && e4 == 0) return TYPE_IMG;
            if (e2 == 'z' && e3 == 0) return TYPE_ARCHIVE;
            if (e2 == 'o' && e3 == 0) return TYPE_CODE;
            break;
        case 'h':
            if (e2 == 0) return TYPE_CODE;
            if (e2 == 't' && e3 == 'm') return TYPE_CODE;
            break;
        case 'i':
            if (e2 == 's' && e3 == 'o' && e4 == 0) return TYPE_ARCHIVE;
            break;
        case 'j':
            if (e2 == 'a' && e3 == 'v' && e4 == 'a') return TYPE_CODE;
            if (e2 == 's' && e3 == 0) return TYPE_CODE;
            if (e2 == 's' && e3 == 'o' && e4 == 'n') return TYPE_CODE;
            if (e2 == 'p' && e3 == 'g') return TYPE_IMG;
            if (e2 == 'p' && e3 == 'e' && e4 == 'g') return TYPE_IMG;
            break;
        case 'k':
            if (e2 == 't' && (e3 == 0 || e3 == 's')) return TYPE_CODE;
            break;
        case 'l':
            if (e2 == 'o' && e3 == 'g') return TYPE_DOC;
            break;
        case 'm':
            if (e2 == 'p' && e3 == '3') return TYPE_AUDIO;
            if (e2 == '4' && e3 == 'a') return TYPE_AUDIO;
            if (e2 == 'i' && e3 == 'd') return TYPE_AUDIO;
            if (e2 == 'p' && e3 == '4') return TYPE_VID;
            if (e2 == 'k' && e3 == 'v') return TYPE_VID;
            if (e2 == 'o' && e3 == 'v') return TYPE_VID;
            if (e2 == 'd' && e3 == 0) return TYPE_CODE;
            break;
        case 'o':
            if (e2 == 'g' && e3 == 'g') return TYPE_AUDIO;
            if (e2 == 'p' && e3 == 'u' && e4 == 's') return TYPE_AUDIO;
            break;
        case 'p':
            if (e2 == 'n' && e3 == 'g') return TYPE_IMG;
            if (e2 == 'd' && e3 == 'f') return TYPE_DOC;
            if (e2 == 'p' && e3 == 't') return TYPE_DOC;
            if (e2 == 'y' && e3 == 0) return TYPE_CODE;
            if (e2 == 'h' && e3 == 'p') return TYPE_CODE;
            break;
        case 'r':
            if (e2 == 'a' && e3 == 'r') return TYPE_ARCHIVE;
            if (e2 == 'b' && e3 == 0) return TYPE_CODE;
            if (e2 == 's' && e3 == 0) return TYPE_CODE;
            break;
        case 's':
            if (e2 == 'h' && e3 == 0) return TYPE_CODE;
            if (e2 == 'q' && e3 == 'l') return TYPE_CODE;
            if (e2 == 'w' && e3 == 'i' && e4 == 'f') return TYPE_CODE;
            break;
        case 't':
            if (e2 == 'x' && e3 == 't') return TYPE_DOC;
            if (e2 == 'a' && e3 == 'r') return TYPE_ARCHIVE;
            if (e2 == 's' && e3 == 0) return TYPE_CODE;
            break;
        case 'w':
            if (e2 == 'a' && e3 == 'v') return TYPE_AUDIO;
            if (e2 == 'e' && e3 == 'b') {
                if (e4 == 'p') return TYPE_IMG;
                if (e4 == 'm') return TYPE_VID;
            }
            if (e2 == 'm' && e3 == 'a') return TYPE_AUDIO;
            break;
        case 'x':
            if (e2 == 'l' && e3 == 's') return TYPE_DOC;
            if (e2 == 'm' && e3 == 'l') return TYPE_CODE;
            if (e2 == 'z' && e3 == 0) return TYPE_ARCHIVE;
            break;
        case 'z':
            if (e2 == 'i' && e3 == 'p') return TYPE_ARCHIVE;
            if (e2 == 's' && e3 == 't') return TYPE_ARCHIVE;
            break;
        case '7':
            if (e2 == 'z') return TYPE_ARCHIVE;
            break;
        case '3':
            if (e2 == 'g' && e3 == 'p') return TYPE_VID;
            break;
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

// ==========================================
// JNI LISTING
// ==========================================
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

    Arena arena;
    arena_init(&arena, 1024 * 1024);

    size_t max_entries = 4096;
    size_t count = 0;
    GlaiveEntry* entries = (GlaiveEntry*)malloc(max_entries * sizeof(GlaiveEntry));

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
                 if (!showHidden) continue;
                 if (d->d_name[1] == 0 || (d->d_name[1] == '.' && d->d_name[2] == 0)) continue;
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
                if (!((1 << type) & filterMask)) continue;
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

            entries[count].name = arena_alloc_str(&arena, d->d_name, name_len);
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
    }

    free(entries);
    arena_destroy(&arena);

    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return (jint)(head - buffer);
}

// ==========================================
// DIR SIZE
// ==========================================
int64_t calculate_dir_size_recursive(int parent_fd, const char *path) {
    int64_t total_size = 0;
    struct stat st;

    int dir_fd = openat(parent_fd, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd == -1) return 0;

    DIR *dir = fdopendir(dir_fd);
    if (!dir) {
        close(dir_fd);
        return 0;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;

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

JNIEXPORT jlong JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeCalculateDirectorySize(JNIEnv *env, jobject clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    if (!path) return 0;
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
// SEARCH UTILS
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
    if (h_len < ctx->qlen) return 0;

#if defined(__ARM_NEON)
    size_t n_len = ctx->qlen;
    uint8x16_t v_lower = ctx->v_first_lower;
    uint8x16_t v_upper = ctx->v_first_upper;
    const char* needle = ctx->query;
    uint8_t first = ctx->first_char_lower;

    size_t i = 0;
    for (; i + 16 <= h_len; i += 16) {
        uint8x16_t block = vld1q_u8((const uint8_t*)(haystack + i));
        uint8x16_t eq = vorrq_u8(vceqq_u8(block, v_lower), vceqq_u8(block, v_upper));
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
#else
    const char* needle = ctx->query;
    size_t n_len = ctx->qlen;
    for (int i = 0; i < h_len; i++) {
        if (tolower(haystack[i]) == ctx->first_char_lower) {
            if (strncasecmp(haystack + i, needle, n_len) == 0) return 1;
        }
    }
#endif
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

        if (pc && (pc == '?' || fold_ci(pc) == tc)) { t++; p++; continue; }
        if (pc == '*') { star = ++p; match = t; continue; }
        if (star) { p = star; t = ++match; continue; }
        return 0;
    }
    while (*p == '*') p++;
    return *p == '\0';
}

static inline int optimized_matches_query(const char *name, int name_len, const SearchContext* ctx) {
    return ctx->glob_mode ? glob_match_ci(name, ctx->query) : optimized_neon_contains(name, name_len, ctx);
}

// ==========================================
// SEARCH RECURSION
// ==========================================
void recursive_scan_optimized(char* path_buf, size_t current_len, size_t base_len,
                              const SearchContext* ctx,
                              unsigned char** head_ptr, unsigned char* end) {
    if (*head_ptr >= end) return;

    int fd = open(path_buf, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) return;

    char kbuf[4096] __attribute__((aligned(8)));
    struct linux_dirent64 *d;
    int nread;

    int is_android = 0;
    if (!ctx->showAppData) {
        if (current_len >= 7 && strcmp(path_buf + current_len - 7, "Android") == 0) {
            if (current_len == 7 || path_buf[current_len - 8] == '/') {
                is_android = 1;
            }
        }
    }

    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            if (*head_ptr >= end) break;

            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            if (d->d_name[0] == '.') {
                if (!ctx->showHidden) { bpos = (bpos < nread) ? bpos : nread; continue; }
                if (d->d_name[1] == 0 || (d->d_name[1] == '.' && d->d_name[2] == 0)) continue;
            }

            int name_len = 0;
            while (d->d_name[name_len]) name_len++;

            if (is_android) {
                if (strcmp(d->d_name, "data") == 0 || strcmp(d->d_name, "obb") == 0) {
                    continue;
                }
            }

            unsigned char type = DT_UNKNOWN;
            if (d->d_type == DT_DIR) type = DT_DIR;
            else if (d->d_type == DT_REG) type = DT_REG;
            else {
                 struct stat st;
                 if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                     if (S_ISDIR(st.st_mode)) type = DT_DIR;
                     else type = DT_REG;
                 }
            }

            if (type == DT_DIR) {
                if (current_len + 1 + name_len < 4096) {
                    path_buf[current_len] = '/';
                    memcpy(path_buf + current_len + 1, d->d_name, name_len + 1);
                    recursive_scan_optimized(path_buf, current_len + 1 + name_len, base_len, ctx, head_ptr, end);
                    path_buf[current_len] = 0;
                }
            } else {
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

    SearchContext ctx;
    ctx.query = query;
    ctx.qlen = strlen(query);
    ctx.glob_mode = has_glob_tokens(query);
    ctx.filterMask = filterMask;
    ctx.showHidden = showHidden;
    ctx.showAppData = showAppData;
    ctx.first_char_lower = (uint8_t)tolower(query[0]);
#if defined(__ARM_NEON)
    ctx.v_first_lower = vdupq_n_u8(ctx.first_char_lower);
    ctx.v_first_upper = vdupq_n_u8((uint8_t)toupper(query[0]));
#endif

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
