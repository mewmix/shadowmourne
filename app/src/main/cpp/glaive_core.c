#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <arm_neon.h>
#include <android/log.h>

#define LOG_TAG "GLAIVE_C"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==========================================
// FAST TYPE HASHING
// ==========================================
// Maps extensions to integers to avoid string comparison chains.
typedef enum {
    TYPE_UNKNOWN = 0,
    TYPE_DIR = 1,
    TYPE_IMG = 2,
    TYPE_VID = 3,
    TYPE_APK = 4,
    TYPE_DOC = 5
} FileType;

static inline unsigned int hash_ext(const char *str) {
    unsigned int hash = 5381;
    int c;
    while ((c = *str++)) hash = ((hash << 5) + hash) + tolower(c);
    return hash;
}

static inline FileType get_type(const char *name, int d_type) {
    if (d_type == DT_DIR) return TYPE_DIR;

    const char *dot = strrchr(name, '.');
    if (!dot) return TYPE_UNKNOWN;
    dot++; // Skip dot

    // Precomputed hashes (DJB2)
    switch (hash_ext(dot)) {
        case 193490416: /* png */
        case 193497021: /* jpg */
        case 2090499160: /* jpeg */ return TYPE_IMG;
        case 193500088: /* mp4 */
        case 193498837: /* mkv */ return TYPE_VID;
        case 193486360: /* apk */ return TYPE_APK;
        case 193499403: /* pdf */ return TYPE_DOC;
        default: return TYPE_UNKNOWN;
    }
}

// ==========================================
// DATA STRUCTURES
// ==========================================
typedef struct {
    char *name;
    char *path;
    int type;
    long size;
    long mtime;
} Entry;

// Custom QSort Comparator: Dirs first, then Name Case-Insensitive
int compare_entries(const void *a, const void *b) {
    Entry *ea = (Entry *)a;
    Entry *eb = (Entry *)b;

    if ((ea->type == TYPE_DIR) != (eb->type == TYPE_DIR)) {
        return (eb->type == TYPE_DIR) - (ea->type == TYPE_DIR);
    }
    return strcasecmp(ea->name, eb->name);
}

// ==========================================
// NEON SEARCH ENGINE (128-bit SIMD)
// ==========================================
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

        // Check 128 bits at once
        uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
        if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
            for (int k = 0; k < 16; k++) {
                if (tolower(haystack[i + k]) == first) {
                    if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                }
            }
        }
    }
    // Cleanup tail
    for (; i < h_len; i++) {
        if (tolower(haystack[i]) == first) {
             if (strncasecmp(haystack + i, needle, n_len) == 0) return 1;
        }
    }
    return 0;
}

// ==========================================
// JNI: LIST DIRECTORY
// ==========================================
JNIEXPORT jobjectArray JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeList(JNIEnv *env, jclass clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    DIR *dir = opendir(path);

    if (!dir) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return NULL;
    }

    // Manual dynamic array (faster than C++ vector here)
    int capacity = 256;
    int count = 0;
    Entry *entries = malloc(sizeof(Entry) * capacity);

    struct dirent *d;
    char full_path[4096];

    while ((d = readdir(dir)) != NULL) {
        if (d->d_name[0] == '.') continue; // Skip hidden

        if (count >= capacity) {
            capacity *= 2;
            entries = realloc(entries, sizeof(Entry) * capacity);
        }

        entries[count].name = strdup(d->d_name);
        entries[count].type = get_type(d->d_name, d->d_type);

        // Construct path for stat
        snprintf(full_path, sizeof(full_path), "%s/%s", path, d->d_name);
        entries[count].path = strdup(full_path);

        // Stat is expensive. Only do it if strictly needed.
        // For MVP listing, we grab it.
        struct stat st;
        if (stat(full_path, &st) == 0) {
            entries[count].size = st.st_size;
            entries[count].mtime = st.st_mtime;
        } else {
            entries[count].size = 0;
            entries[count].mtime = 0;
        }

        count++;
    }
    closedir(dir);

    // Native Sort
    qsort(entries, count, sizeof(Entry), compare_entries);

    // Convert to Java Array
    jclass cls = (*env)->FindClass(env, "com/mewmix/glaive/data/GlaiveItem");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IJJ)V");
    jobjectArray result = (*env)->NewObjectArray(env, count, cls, NULL);

    for (int i = 0; i < count; i++) {
        jstring jName = (*env)->NewStringUTF(env, entries[i].name);
        jstring jFullPath = (*env)->NewStringUTF(env, entries[i].path);

        jobject item = (*env)->NewObject(env, cls, ctor,
            jName, jFullPath, entries[i].type, (jlong)entries[i].size, (jlong)entries[i].mtime);

        (*env)->SetObjectArrayElement(env, result, i, item);

        (*env)->DeleteLocalRef(env, jName);
        (*env)->DeleteLocalRef(env, jFullPath);
        (*env)->DeleteLocalRef(env, item);

        free(entries[i].name);
        free(entries[i].path);
    }

    free(entries);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return result;
}

// ==========================================
// JNI: SEARCH (Recursive)
// ==========================================
// Simplified implementation for brevity
void recursive_scan(const char* base, const char* query, size_t qlen, JNIEnv *env, jobjectArray result, int* idx, int max, jclass cls, jmethodID ctor) {
    if (*idx >= max) return;

    DIR* dir = opendir(base);
    if (!dir) return;

    struct dirent* d;
    char path[4096];

    while ((d = readdir(dir)) != NULL && *idx < max) {
        if (d->d_name[0] == '.') continue;

        snprintf(path, sizeof(path), "%s/%s", base, d->d_name);

        if (d->d_type == DT_DIR) {
             if (strcmp(d->d_name, "Android") != 0) { // Skip Android data
                 recursive_scan(path, query, qlen, env, result, idx, max, cls, ctor);
             }
        } else {
            if (neon_contains(d->d_name, query, qlen)) {
                // Match found
                jstring jName = (*env)->NewStringUTF(env, d->d_name);
                jstring jPath = (*env)->NewStringUTF(env, path);
                // Mock size/time for search speed
                jobject item = (*env)->NewObject(env, cls, ctor, jName, jPath, get_type(d->d_name, d->d_type), (jlong)0, (jlong)0);
                (*env)->SetObjectArrayElement(env, result, *idx, item);
                (*idx)++;

                (*env)->DeleteLocalRef(env, jName);
                (*env)->DeleteLocalRef(env, jPath);
                (*env)->DeleteLocalRef(env, item);
            }
        }
    }
    closedir(dir);
}

JNIEXPORT jobjectArray JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeSearch(JNIEnv *env, jclass clazz, jstring jRoot, jstring jQuery) {
    const char *root = (*env)->GetStringUTFChars(env, jRoot, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jQuery, NULL);

    jclass cls = (*env)->FindClass(env, "com/mewmix/glaive/data/GlaiveItem");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IJJ)V");
    jobjectArray result = (*env)->NewObjectArray(env, 500, cls, NULL); // Cap at 500

    int count = 0;
    recursive_scan(root, query, strlen(query), env, result, &count, 500, cls, ctor);

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    return result;
}
