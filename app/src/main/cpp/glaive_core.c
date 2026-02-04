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
#include <time.h>
#include <pthread.h>
#include <stdatomic.h>

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

// ==========================================
// GLOBALS & SYNC
// ==========================================
static volatile atomic_int g_cancel_search = 0;

JNIEXPORT void JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeCancelSearch(JNIEnv *env, jobject clazz) {
    atomic_store(&g_cancel_search, 1);
}

JNIEXPORT void JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeResetSearch(JNIEnv *env, jobject clazz) {
    atomic_store(&g_cancel_search, 0);
}

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
    // Dual-filter for SIMD
    int use_second;
    uint8_t second_char_lower;
    uint8x16_t v_second_lower;
    uint8x16_t v_second_upper;

    int filterMask;
} SearchContext;

// ==========================================
// WORK QUEUE
// ==========================================
typedef struct WorkItem {
    char* path;
    size_t len;
    struct WorkItem* next;
} WorkItem;

typedef struct {
    WorkItem* head;
    WorkItem* tail;
    int count;
    int active_workers;
    int shutdown;
    pthread_mutex_t lock;
    pthread_cond_t cond;
} WorkQueue;

static void queue_init(WorkQueue* q) {
    q->head = q->tail = NULL;
    q->count = 0;
    q->active_workers = 0;
    q->shutdown = 0;
    pthread_mutex_init(&q->lock, NULL);
    pthread_cond_init(&q->cond, NULL);
}

static void queue_push(WorkQueue* q, char* path, size_t len) {
    WorkItem* item = (WorkItem*)malloc(sizeof(WorkItem));
    item->path = path;
    item->len = len;
    item->next = NULL;

    pthread_mutex_lock(&q->lock);
    if (q->tail) {
        q->tail->next = item;
        q->tail = item;
    } else {
        q->head = q->tail = item;
    }
    q->count++;
    pthread_cond_signal(&q->cond);
    pthread_mutex_unlock(&q->lock);
}

static WorkItem* queue_pop(WorkQueue* q) {
    pthread_mutex_lock(&q->lock);
    while (q->count == 0 && !q->shutdown) {
        if (q->active_workers == 0) {
            // No work left and no workers active -> Done
            q->shutdown = 1;
            pthread_cond_broadcast(&q->cond);
            pthread_mutex_unlock(&q->lock);
            return NULL;
        }
        pthread_cond_wait(&q->cond, &q->lock);
    }

    if (q->shutdown) {
        pthread_mutex_unlock(&q->lock);
        return NULL;
    }

    WorkItem* item = q->head;
    if (item) {
        q->head = item->next;
        if (!q->head) q->tail = NULL;
        q->count--;
        q->active_workers++;
    }
    pthread_mutex_unlock(&q->lock);
    return item;
}

static void queue_worker_done(WorkQueue* q) {
    pthread_mutex_lock(&q->lock);
    q->active_workers--;
    if (q->count == 0 && q->active_workers == 0) {
        q->shutdown = 1;
        pthread_cond_broadcast(&q->cond);
    }
    pthread_mutex_unlock(&q->lock);
}

static void queue_destroy(WorkQueue* q) {
    pthread_mutex_destroy(&q->lock);
    pthread_cond_destroy(&q->cond);
    WorkItem* curr = q->head;
    while(curr) {
        WorkItem* next = curr->next;
        free(curr->path);
        free(curr);
        curr = next;
    }
}

// ==========================================
// RESULT BUFFER
// ==========================================
typedef struct {
    unsigned char* start;
    unsigned char* current;
    unsigned char* end;
    pthread_mutex_t lock;
    size_t base_len;
} GlobalBuffer;

static void gbuf_init(GlobalBuffer* gb, unsigned char* buf, int cap, size_t base_len) {
    gb->start = buf;
    gb->current = buf;
    gb->end = buf + cap;
    gb->base_len = base_len;
    pthread_mutex_init(&gb->lock, NULL);
}

static void gbuf_write(GlobalBuffer* gb, const unsigned char* data, size_t len) {
    pthread_mutex_lock(&gb->lock);
    if (gb->current + len <= gb->end) {
        memcpy(gb->current, data, len);
        gb->current += len;
    }
    pthread_mutex_unlock(&gb->lock);
}

static void gbuf_destroy(GlobalBuffer* gb) {
    pthread_mutex_destroy(&gb->lock);
}

// ==========================================
// HELPERS
// ==========================================

static inline int has_glob_tokens(const char *pattern) {
    while (*pattern) {
        if (*pattern == '*' || *pattern == '?') return 1;
        pattern++;
    }
    return 0;
}

static void setup_search_context(SearchContext* ctx, const char* query, int filterMask) {
    ctx->query = query;
    ctx->qlen = strlen(query);
    ctx->glob_mode = has_glob_tokens(query);
    ctx->filterMask = filterMask;
    ctx->use_second = 0;

    if (ctx->glob_mode && ctx->qlen > 2 && ctx->query[0] == '*' && ctx->query[ctx->qlen - 1] == '*') {
        int internal_wildcard = 0;
        for (size_t k = 1; k < ctx->qlen - 1; k++) {
            if (ctx->query[k] == '*' || ctx->query[k] == '?') {
                internal_wildcard = 1;
                break;
            }
        }
        if (!internal_wildcard) {
            ctx->query++;
            ctx->qlen -= 2;
            ctx->glob_mode = 0;
        }
    }

    const char* effective_query = ctx->query;
    ctx->first_char_lower = (uint8_t)tolower(effective_query[0]);
    ctx->v_first_lower = vdupq_n_u8(ctx->first_char_lower);
    ctx->v_first_upper = vdupq_n_u8((uint8_t)toupper(effective_query[0]));

    if (ctx->qlen >= 2) {
        ctx->use_second = 1;
        ctx->second_char_lower = (uint8_t)tolower(effective_query[1]);
        ctx->v_second_lower = vdupq_n_u8(ctx->second_char_lower);
        ctx->v_second_upper = vdupq_n_u8((uint8_t)toupper(effective_query[1]));
    }
}

static inline unsigned char fold_ci(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return (unsigned char)(c + 32);
    return c;
}

static int optimized_neon_contains(const char *haystack, int h_len, const SearchContext* ctx) {
    if (h_len < ctx->qlen) return 0;
    size_t n_len = ctx->qlen;
    const char* needle = ctx->query;
    uint8_t first = ctx->first_char_lower;
    size_t i = 0;

    if (ctx->use_second) {
        uint8x16_t v1_l = ctx->v_first_lower;
        uint8x16_t v1_u = ctx->v_first_upper;
        uint8x16_t v2_l = ctx->v_second_lower;
        uint8x16_t v2_u = ctx->v_second_upper;
        uint8_t second = ctx->second_char_lower;
        for (; i + 16 <= h_len; i += 16) {
            uint8x16_t block1 = vld1q_u8((const uint8_t*)(haystack + i));
            uint8x16_t block2 = vld1q_u8((const uint8_t*)(haystack + i + 1));
            uint8x16_t eq1 = vorrq_u8(vceqq_u8(block1, v1_l), vceqq_u8(block1, v1_u));
            uint8x16_t eq2 = vorrq_u8(vceqq_u8(block2, v2_l), vceqq_u8(block2, v2_u));
            uint8x16_t eq = vandq_u8(eq1, eq2);
            uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
            if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
                for (int k = 0; k < 16; k++) {
                    if (tolower(haystack[i + k]) == first && tolower(haystack[i + k + 1]) == second) {
                        if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                    }
                }
            }
        }
    } else {
        uint8x16_t v_lower = ctx->v_first_lower;
        uint8x16_t v_upper = ctx->v_first_upper;
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
            t++; p++; continue;
        }
        if (pc == '*') {
            star = ++p; match = t; continue;
        }
        if (star) {
            p = star; t = ++match; continue;
        }
        return 0;
    }
    while (*p == '*') p++;
    return *p == '\0';
}

static inline int optimized_matches_query(const char *name, int name_len, const SearchContext* ctx) {
    return ctx->glob_mode ? glob_match_ci(name, ctx->query) : optimized_neon_contains(name, name_len, ctx);
}

static inline unsigned char fast_get_type(const char *name, int name_len) {
    if (name_len < 4) return TYPE_FILE;
    const char *ext = name + name_len - 1;
    int i = 0;
    while (i < 6 && ext > name) {
        if (*ext == '.') break;
        ext--; i++;
    }
    if (*ext != '.') return TYPE_FILE;
    char e1 = tolower(ext[1]);
    char e2 = tolower(ext[2]);
    char e3 = tolower(ext[3]);
    char e4 = (i >= 4) ? tolower(ext[4]) : 0;

    if (e1 == 'p') {
        if (e2 == 'n' && e3 == 'g') return TYPE_IMG;
        if (e2 == 'd' && e3 == 'f') return TYPE_DOC;
        if (e2 == 'p' && e3 == 't') return TYPE_DOC;
    }
    if (e1 == 'j') {
        if (e2 == 'p' && e3 == 'g') return TYPE_IMG;
        if (e2 == 'p' && e3 == 'e' && e4 == 'g') return TYPE_IMG;
    }
    if (e1 == 'm') {
        if (e2 == 'p' && e3 == '4') return TYPE_VID;
        if (e2 == 'k' && e3 == 'v') return TYPE_VID;
        if (e2 == 'o' && e3 == 'v') return TYPE_VID;
    }
    if (e1 == 'a') {
        if (e2 == 'p' && e3 == 'k') return TYPE_APK;
        if (e2 == 'v' && e3 == 'i') return TYPE_VID;
    }
    if (e1 == 'g') {
        if (e2 == 'i' && e3 == 'f') return TYPE_IMG;
    }
    if (e1 == 'w') {
        if (e2 == 'e' && e3 == 'b') {
            if (e4 == 'p') return TYPE_IMG;
            if (e4 == 'm') return TYPE_VID;
        }
    }
    if (e1 == 'd') {
        if (e2 == 'o' && e3 == 'c') return TYPE_DOC;
    }
    if (e1 == 'x') {
        if (e2 == 'l' && e3 == 's') return TYPE_DOC;
    }
    if (e1 == 't') {
        if (e2 == 'x' && e3 == 't') return TYPE_DOC;
    }
    if (e1 == '3') {
        if (e2 == 'g' && e3 == 'p') return TYPE_VID;
    }
    if (e1 == 'b') {
        if (e2 == 'm' && e3 == 'p') return TYPE_IMG;
    }
    return TYPE_FILE;
}

// ==========================================
// LISTING (RESTORED)
// ==========================================
typedef struct {
    char* name;
    int name_len;
    unsigned char type;
    int64_t size;
    int64_t time;
} GlaiveEntry;

typedef struct {
    int dirfd;
    GlaiveEntry* entries;
    size_t start_index;
    size_t end_index;
} StatWorkerArgs;

void* stat_worker_thread(void* arg) {
    StatWorkerArgs* args = (StatWorkerArgs*)arg;
    struct stat st;
    for (size_t i = args->start_index; i < args->end_index; i++) {
        GlaiveEntry* e = &args->entries[i];
        if (fstatat(args->dirfd, e->name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
            e->size = st.st_size;
            e->time = st.st_mtime;
            if (e->type == TYPE_UNKNOWN || e->type == TYPE_FILE) {
                 if (S_ISDIR(st.st_mode)) e->type = TYPE_DIR;
                 else if (e->type == TYPE_UNKNOWN) e->type = fast_get_type(e->name, e->name_len);
            }
        } else {
             if (e->type == TYPE_UNKNOWN) e->type = fast_get_type(e->name, e->name_len);
        }
    }
    return NULL;
}

static int g_sort_mode = 0;
static int g_sort_asc = 1;

int compare_entries(const void* a, const void* b) {
    GlaiveEntry* ea = (GlaiveEntry*)a;
    GlaiveEntry* eb = (GlaiveEntry*)b;

    if (ea->type == TYPE_DIR && eb->type != TYPE_DIR) return -1;
    if (ea->type != TYPE_DIR && eb->type == TYPE_DIR) return 1;

    int result = 0;
    switch (g_sort_mode) {
        case 0: result = strcasecmp(ea->name, eb->name); break;
        case 2:
            if (ea->size < eb->size) result = -1;
            else if (ea->size > eb->size) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        case 1:
            if (ea->time < eb->time) result = -1;
            else if (ea->time > eb->time) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        case 3:
            if (ea->type < eb->type) result = -1;
            else if (ea->type > eb->type) result = 1;
            else result = strcasecmp(ea->name, eb->name);
            break;
        default: result = strcasecmp(ea->name, eb->name);
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
    int nread;

    // PHASE 1: READ ENTRIES (SERIAL)
    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            if (d->d_name[0] == '.') continue;

            int name_len = 0;
            while (d->d_name[name_len] && name_len < 255) name_len++;

            unsigned char type = TYPE_UNKNOWN;
            if (d->d_type == DT_DIR) type = TYPE_DIR;
            else if (d->d_type == DT_REG) type = fast_get_type(d->d_name, name_len);

            // Filter early if type is known
            if (filterMask != 0 && type != TYPE_UNKNOWN && type != TYPE_DIR) {
                if (!((1 << type) & filterMask)) continue;
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
            entries[count].size = 0;
            entries[count].time = 0;
            count++;
        }
    }

    // PHASE 2: STAT (PARALLEL)
    if (count > 0) {
        #define NUM_STAT_THREADS 4
        if (count < 100) {
             StatWorkerArgs args = { .dirfd = fd, .entries = entries, .start_index = 0, .end_index = count };
             stat_worker_thread(&args);
        } else {
            pthread_t threads[NUM_STAT_THREADS];
            int created[NUM_STAT_THREADS];
            StatWorkerArgs args[NUM_STAT_THREADS];
            size_t chunk = count / NUM_STAT_THREADS;

            for (int i = 0; i < NUM_STAT_THREADS; i++) {
                args[i].dirfd = fd;
                args[i].entries = entries;
                args[i].start_index = i * chunk;
                args[i].end_index = (i == NUM_STAT_THREADS - 1) ? count : (i + 1) * chunk;
                if (pthread_create(&threads[i], NULL, stat_worker_thread, &args[i]) == 0) {
                    created[i] = 1;
                } else {
                    created[i] = 0;
                    stat_worker_thread(&args[i]);
                }
            }
            for (int i = 0; i < NUM_STAT_THREADS; i++) {
                if (created[i]) pthread_join(threads[i], NULL);
            }
        }
    }
    close(fd);

    // PHASE 3: SORT & OUTPUT (SERIAL)
    g_sort_mode = sortMode;
    g_sort_asc = asc;
    qsort(entries, count, sizeof(GlaiveEntry), compare_entries);

    unsigned char *head = buffer;
    unsigned char *end = buffer + capacity;
    
    size_t i = 0;
    for (; i < count; i++) {
        if (filterMask != 0 && entries[i].type != TYPE_DIR) {
             if (!((1 << entries[i].type) & filterMask)) {
                 free(entries[i].name);
                 continue;
             }
        }

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
    // Clean up remaining entries if buffer was full
    for (; i < count; i++) {
        free(entries[i].name);
    }
    free(entries);

    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return (jint)(head - buffer);
}

// ==========================================
// WORKER
// ==========================================
typedef struct {
    WorkQueue* queue;
    GlobalBuffer* gbuf;
    const SearchContext* ctx;
} WorkerArgs;

#define LOCAL_BUF_SIZE 16384

void* worker_thread(void* arg) {
    WorkerArgs* args = (WorkerArgs*)arg;
    WorkQueue* q = args->queue;
    GlobalBuffer* gbuf = args->gbuf;
    const SearchContext* ctx = args->ctx;

    unsigned char local_buf[LOCAL_BUF_SIZE];
    unsigned char* head = local_buf;
    unsigned char* end = local_buf + LOCAL_BUF_SIZE;

    while (1) {
        if (atomic_load(&g_cancel_search)) {
            pthread_mutex_lock(&q->lock);
            q->shutdown = 1;
            pthread_cond_broadcast(&q->cond);
            pthread_mutex_unlock(&q->lock);
            break;
        }

        WorkItem* item = queue_pop(q);
        if (!item) break;

        int fd = open(item->path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd != -1) {
            char kbuf[4096] __attribute__((aligned(8)));
            struct linux_dirent64 *d;
            int nread;

            while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
                if (atomic_load(&g_cancel_search)) break;

                int bpos = 0;
                while (bpos < nread) {
                    d = (struct linux_dirent64 *)(kbuf + bpos);
                    bpos += d->d_reclen;
                    if (d->d_name[0] == '.') continue;

                    int name_len = 0;
                    while (d->d_name[name_len]) name_len++;

                    unsigned char type = DT_UNKNOWN;
                    if (d->d_type == DT_DIR) type = DT_DIR;
                    else if (d->d_type == DT_REG) type = DT_REG;
                    else {
                        struct stat st;
                        if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                            if (S_ISDIR(st.st_mode)) type = DT_DIR; else type = DT_REG;
                        }
                    }

                    if (type == DT_DIR) {
                         size_t child_len = item->len + 1 + name_len;
                         char* child_path = malloc(child_len + 1);
                         memcpy(child_path, item->path, item->len);
                         child_path[item->len] = '/';
                         memcpy(child_path + item->len + 1, d->d_name, name_len + 1);
                         queue_push(q, child_path, child_len);
                    } else {
                        if (optimized_matches_query(d->d_name, name_len, ctx)) {
                            unsigned char g_type = fast_get_type(d->d_name, name_len);
                            if (ctx->filterMask != 0) {
                                if (!((1 << g_type) & ctx->filterMask)) continue;
                            }

                            size_t full_len = item->len + 1 + name_len;
                            size_t rel_len = full_len - (gbuf->base_len + 1);

                            if (head + 18 + rel_len > end) {
                                gbuf_write(gbuf, local_buf, head - local_buf);
                                head = local_buf;
                            }

                            int proto_len = (rel_len > 255) ? 255 : (int)rel_len;
                            if (head + 18 + proto_len <= end) {
                                *head++ = g_type;
                                *head++ = (unsigned char)proto_len;

                                char* base_ptr = item->path + gbuf->base_len + 1;
                                size_t prefix_len = item->len - (gbuf->base_len + 1);
                                if (prefix_len > 0) {
                                    size_t copy_len = (prefix_len > proto_len) ? proto_len : prefix_len;
                                    memcpy(head, base_ptr, copy_len);
                                    if (copy_len < proto_len) {
                                        head[copy_len] = '/';
                                        size_t rem = proto_len - copy_len - 1;
                                        if (rem > name_len) rem = name_len;
                                        memcpy(head + copy_len + 1, d->d_name, rem);
                                    }
                                } else {
                                     size_t copy_len = (name_len > proto_len) ? proto_len : name_len;
                                     memcpy(head, d->d_name, copy_len);
                                }

                                head += proto_len;
                                memset(head, 0, 16);
                                head += 16;
                            }
                        }
                    }
                }
            }
            close(fd);
        }
        free(item->path);
        free(item);
        queue_worker_done(q);
    }
    if (head > local_buf) {
        gbuf_write(gbuf, local_buf, head - local_buf);
    }
    return NULL;
}

// ==========================================
// JNI INTERFACE (SEARCH)
// ==========================================

JNIEXPORT jint JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeSearch(JNIEnv *env, jobject clazz, jstring jRoot, jstring jQuery, jobject jBuffer, jint capacity, jint filterMask) {
    if (capacity <= 0) return 0;
    if (atomic_load(&g_cancel_search)) return 0;

    const char *root = (*env)->GetStringUTFChars(env, jRoot, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jQuery, NULL);
    unsigned char *buffer = (*env)->GetDirectBufferAddress(env, jBuffer);

    if (!buffer) {
        (*env)->ReleaseStringUTFChars(env, jRoot, root);
        (*env)->ReleaseStringUTFChars(env, jQuery, query);
        return -2;
    }

    SearchContext ctx;
    setup_search_context(&ctx, query, filterMask);

    size_t root_len = strlen(root);
    size_t base_len = root_len;
    if (base_len > 1 && root[base_len - 1] == '/') base_len--;

    GlobalBuffer gbuf;
    gbuf_init(&gbuf, buffer, capacity, base_len);

    WorkQueue q;
    queue_init(&q);

    char* root_dup = malloc(base_len + 1);
    memcpy(root_dup, root, base_len);
    root_dup[base_len] = 0;
    queue_push(&q, root_dup, base_len);

    #define NUM_THREADS 4
    pthread_t threads[NUM_THREADS];
    WorkerArgs args = { .queue = &q, .gbuf = &gbuf, .ctx = &ctx };

    for (int i = 0; i < NUM_THREADS; i++) {
        pthread_create(&threads[i], NULL, worker_thread, &args);
    }
    for (int i = 0; i < NUM_THREADS; i++) {
        pthread_join(threads[i], NULL);
    }

    queue_destroy(&q);
    int result_len = (int)(gbuf.current - gbuf.start);
    gbuf_destroy(&gbuf);

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    return result_len;
}

// ==========================================
// LEGACY / UTILS
// ==========================================

int64_t calculate_dir_size_recursive(int parent_fd, const char *path) {
    int64_t total_size = 0;
    struct stat st;
    int dir_fd = openat(parent_fd, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd == -1) return 0;
    char kbuf[8192] __attribute__((aligned(8)));
    struct linux_dirent64 *d;
    int nread;
    while ((nread = syscall(__NR_getdents64, dir_fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;
            if (d->d_name[0] == '.') {
                if (d->d_name[1] == 0) continue;
                if (d->d_name[1] == '.' && d->d_name[2] == 0) continue;
            }
            unsigned char type = d->d_type;
            if (type == DT_UNKNOWN) {
                if (fstatat(dir_fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                    if (S_ISDIR(st.st_mode)) type = DT_DIR; else type = DT_REG;
                }
            }
            if (type == DT_DIR) {
                total_size += calculate_dir_size_recursive(dir_fd, d->d_name);
            } else {
                if (d->d_type == DT_UNKNOWN) {
                     if (!S_ISDIR(st.st_mode)) total_size += st.st_size;
                } else {
                     if (fstatat(dir_fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) total_size += st.st_size;
                }
            }
        }
    }
    close(dir_fd);
    return total_size;
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

static void create_benchmark_files(const char* base_path) {
    mkdir(base_path, 0777);
    char path[4096];
    for (int i = 0; i < 20; i++) {
        snprintf(path, sizeof(path), "%s/dir_%d", base_path, i);
        mkdir(path, 0777);
        for (int j = 0; j < 500; j++) {
            char fpath[4096];
            snprintf(fpath, sizeof(fpath), "%s/file_%d_%d.txt", path, i, j);
            if (access(fpath, F_OK) == -1) {
                int fd = open(fpath, O_CREAT | O_WRONLY, 0666);
                if (fd != -1) close(fd);
            }
        }
    }
}

JNIEXPORT void JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeRunBenchmark(JNIEnv *env, jobject clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    char bench_path[4096];
    snprintf(bench_path, sizeof(bench_path), "%s/BENCHMARK", path);
    LOGE("BENCHMARK STARTING at %s", bench_path);
    create_benchmark_files(bench_path);
    int fd = open(bench_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd != -1) {
        int64_t size = calculate_dir_size_recursive(fd, ".");
        LOGE("BENCHMARK DIR SIZE: %lld", (long long)size);
        close(fd);
    }
    (*env)->ReleaseStringUTFChars(env, jPath, path);
}
