/*
 * Copyright (c) 2008, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "jni.h"
#include "jni_util.h"
#include "jvm.h"
#include "jlong.h"

#include <sys/param.h>
#include <sys/mount.h>
#if defined(__FreeBSD__) || defined(__NetBSD__)
#include <sys/types.h>
#include <sys/extattr.h>
#endif
#ifdef ST_RDONLY
#define statfs statvfs
#define getfsstat getvfsstat
#define f_flags f_flag
#define ISREADONLY ST_RDONLY
#else
#define ISREADONLY MNT_RDONLY
#endif

#include <stdlib.h>
#include <string.h>

static jfieldID entry_name;
static jfieldID entry_dir;
static jfieldID entry_fstype;
static jfieldID entry_options;

struct fsstat_iter {
    struct statfs *buf;
    int pos;
    int nentries;
};

#include "sun_nio_fs_BsdNativeDispatcher.h"

static void throwUnixException(JNIEnv* env, int errnum) {
    jobject x = JNU_NewObjectByName(env, "sun/nio/fs/UnixException",
        "(I)V", errnum);
    if (x != NULL) {
        (*env)->Throw(env, x);
    }
}

/**
 * Initialize jfieldIDs
 */
JNIEXPORT void JNICALL
Java_sun_nio_fs_BsdNativeDispatcher_initIDs(JNIEnv* env, jclass this)
{
    jclass clazz;

    clazz = (*env)->FindClass(env, "sun/nio/fs/UnixMountEntry");
    CHECK_NULL(clazz);
    entry_name = (*env)->GetFieldID(env, clazz, "name", "[B");
    CHECK_NULL(entry_name);
    entry_dir = (*env)->GetFieldID(env, clazz, "dir", "[B");
    CHECK_NULL(entry_dir);
    entry_fstype = (*env)->GetFieldID(env, clazz, "fstype", "[B");
    CHECK_NULL(entry_fstype);
    entry_options = (*env)->GetFieldID(env, clazz, "opts", "[B");
    CHECK_NULL(entry_options);
}

JNIEXPORT jlong JNICALL
Java_sun_nio_fs_BsdNativeDispatcher_getfsstat(JNIEnv* env, jclass this)
{
    int nentries;
    size_t bufsize;
    struct fsstat_iter *iter = malloc(sizeof(*iter));

    if (iter == NULL) {
        JNU_ThrowOutOfMemoryError(env, "native heap");
        return 0;
    }

    iter->pos = 0;
    iter->nentries = 0;
    iter->buf = NULL;

    nentries = getfsstat(NULL, 0, MNT_NOWAIT);

    if (nentries <= 0) {
        free(iter);
        throwUnixException(env, errno);
        return 0;
    }

    // It's possible that a new filesystem gets mounted between
    // the first getfsstat and the second so loop until consistent

    while (nentries != iter->nentries) {
        if (iter->buf != NULL)
            free(iter->buf);

        bufsize = nentries * sizeof(struct statfs);
        iter->nentries = nentries;

        iter->buf = malloc(bufsize);
        if (iter->buf == NULL) {
            free(iter);
            JNU_ThrowOutOfMemoryError(env, "native heap");
            return 0;
        }

        nentries = getfsstat(iter->buf, bufsize, MNT_WAIT);
        if (nentries <= 0) {
            free(iter->buf);
            free(iter);
            throwUnixException(env, errno);
            return 0;
        }
    }

    return (jlong)iter;
}

JNIEXPORT jint JNICALL
Java_sun_nio_fs_BsdNativeDispatcher_fsstatEntry(JNIEnv* env, jclass this,
    jlong value, jobject entry)
{
    struct fsstat_iter *iter = jlong_to_ptr(value);
    jsize len;
    jbyteArray bytes;
    char* name;
    char* dir;
    char* fstype;
    char* options;

    if (iter == NULL || iter->pos >= iter->nentries)
       return -1;

    name = iter->buf[iter->pos].f_mntfromname;
    dir = iter->buf[iter->pos].f_mntonname;
    fstype = iter->buf[iter->pos].f_fstypename;
    if (iter->buf[iter->pos].f_flags & ISREADONLY)
        options="ro";
    else
        options="";

    iter->pos++;

    len = strlen(name);
    bytes = (*env)->NewByteArray(env, len);
    if (bytes == NULL)
        return -1;
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (jbyte*)name);
    (*env)->SetObjectField(env, entry, entry_name, bytes);

    len = strlen(dir);
    bytes = (*env)->NewByteArray(env, len);
    if (bytes == NULL)
        return -1;
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (jbyte*)dir);
    (*env)->SetObjectField(env, entry, entry_dir, bytes);

    len = strlen(fstype);
    bytes = (*env)->NewByteArray(env, len);
    if (bytes == NULL)
        return -1;
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (jbyte*)fstype);
    (*env)->SetObjectField(env, entry, entry_fstype, bytes);

    len = strlen(options);
    bytes = (*env)->NewByteArray(env, len);
    if (bytes == NULL)
        return -1;
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (jbyte*)options);
    (*env)->SetObjectField(env, entry, entry_options, bytes);

    return 0;
}

JNIEXPORT void JNICALL
Java_sun_nio_fs_BsdNativeDispatcher_endfsstat(JNIEnv* env, jclass this, jlong value)
{
    struct fsstat_iter *iter = jlong_to_ptr(value);

    if (iter != NULL) {
        free(iter->buf);
        free(iter);
    }
}

/*
 * The following methods are part of the UnixNativeDispatcher parent class,
 * but is placed here since they contain platform specific implementations.
 */

JNIEXPORT jint JNICALL
Java_sun_nio_fs_UnixNativeDispatcher_fgetxattr0(JNIEnv* env, jclass clazz,
    jint fd, jlong nameAddress, jlong valueAddress, jint valueLen)
{
    ssize_t res = -1;
#ifndef __OpenBSD__
    const char* name = jlong_to_ptr(nameAddress);
    void* value = jlong_to_ptr(valueAddress);

    // name will allwaye be prefixed with 'user.', so we pass only the part
    // after the prefix to the extattr_get_fd function.
    const char* attr_name = strchr(name, '.') + 1;
    res = extattr_get_fd(fd, EXTATTR_NAMESPACE_USER, attr_name, value, (size_t)valueLen);
    if (res == (ssize_t)-1)
        throwUnixException(env, errno);
#else
    throwUnixException(env, ENOTSUP);
#endif

    return (jint)res;
}

JNIEXPORT void JNICALL
Java_sun_nio_fs_UnixNativeDispatcher_fsetxattr0(JNIEnv* env, jclass clazz,
    jint fd, jlong nameAddress, jlong valueAddress, jint valueLen)
{
#ifndef __OpenBSD__
    int res = -1;
    const char* name = jlong_to_ptr(nameAddress);
    void* value = jlong_to_ptr(valueAddress);

    // name will allwaye be prefixed with 'user.', so we pass only the part
    // after the prefix to the extattr_set_fd function.
    const char* attr_name = strchr(name, '.') + 1;
    res = extattr_set_fd(fd, EXTATTR_NAMESPACE_USER, attr_name, value, (size_t)valueLen);
    if (res == -1)
        throwUnixException(env, errno);
#else
    throwUnixException(env, ENOTSUP);
#endif
}

JNIEXPORT void JNICALL
Java_sun_nio_fs_UnixNativeDispatcher_fremovexattr0(JNIEnv* env, jclass clazz,
    jint fd, jlong nameAddress)
{
#ifndef __OpenBSD__
    int res = -1;
    const char* name = jlong_to_ptr(nameAddress);

    // name will allwaye be prefixed with 'user.', so we pass only the part
    // after the prefix to the extattr_delete_fd function.
    const char* attr_name = strchr(name, '.') + 1;
    res = extattr_delete_fd(fd, EXTATTR_NAMESPACE_USER, attr_name);
    if (res == -1)
        throwUnixException(env, errno);
#else
    throwUnixException(env, ENOTSUP);
#endif
}

JNIEXPORT jint JNICALL
Java_sun_nio_fs_UnixNativeDispatcher_flistxattr(JNIEnv* env, jclass clazz,
    jint fd, jlong listAddress, jint size)
{
    ssize_t res = -1;
#ifndef __OpenBSD__
    char* list = jlong_to_ptr(listAddress);

    // The UnixNativeDispatcher class expects a buffer of zero-terminated names
    // where each is prefixed with 'user.'. On BSD we get a buffer with
    // length-prefixed names, and without the 'user.' prefix for each
    // attribute. So we need to convert to the format expected by the calling
    // class.

    // The BSD list should allways be smaller than the resulting list, so it
    // should be safe to use the same max size.
    char* bsd_list = list ? malloc(size) : NULL;

    ssize_t bytes_read = extattr_list_fd(fd, EXTATTR_NAMESPACE_USER, bsd_list, (size_t)size);
    if (bytes_read == (ssize_t)-1) {
        throwUnixException(env, errno);
    }

    if (list != NULL) {
        //
        // Convert the buffer to the expected format
        //
        const char* prefix = "user.";
        ssize_t prefixlen = strlen(prefix);

        char* dest = list;
        char* src = bsd_list;
        ssize_t remaining = size;

        while (src - bsd_list < bytes_read) {
            ssize_t len = *src++;

            if (len + prefixlen < remaining) {
                char* start = dest;

                memcpy(dest, prefix, prefixlen);
                memcpy(dest + prefixlen, src, len);

                dest += prefixlen + len;
                *dest++ = 0;

                src += len;
                remaining -= dest - start;
            }
        }

        res = dest - list;

        free(bsd_list);
    }

#else // !__OpenBSD__
    throwUnixException(env, ENOTSUP);
#endif // !__OpenBSD__

    return (jint)res;
}
