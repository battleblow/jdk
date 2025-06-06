/*
 * Copyright (c) 2008, 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.fs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;

import jdk.internal.access.JavaNioAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;

import static sun.nio.fs.UnixConstants.*;
import static sun.nio.fs.UnixNativeDispatcher.*;

/**
 * Unix implementation of UserDefinedFileAttributeView using extended attributes.
 */
abstract class UnixUserDefinedFileAttributeView
    extends AbstractUserDefinedFileAttributeView
{
    enum FileAttributeType {
        LINUX,
        BSD
    };

    private static final Unsafe unsafe = Unsafe.getUnsafe();

    private static final JavaNioAccess NIO_ACCESS = SharedSecrets.getJavaNioAccess();

    // namespace for extended user attributes
    private static final String USER_NAMESPACE = "user.";

    private static final int MIN_LISTXATTR_BUF_SIZE = 1024;
    private static final int MAX_LISTXATTR_BUF_SIZE = 32 * 1024;

    private final UnixPath file;
    private final boolean followLinks;
    private final FileAttributeType attributeType;

    UnixUserDefinedFileAttributeView(UnixPath file, boolean followLinks) {
        this(file, followLinks, FileAttributeType.LINUX);
    }

    UnixUserDefinedFileAttributeView(UnixPath file, boolean followLinks, FileAttributeType attributeType) {
        this.file = file;
        this.followLinks = followLinks;
        this.attributeType = attributeType;
    }

    private byte[] nameAsBytes(UnixPath file, String name) throws IOException {
        if (name == null)
            throw new NullPointerException("'name' is null");
        if (attributeType == FileAttributeType.LINUX) {
            name = USER_NAMESPACE + name;
        }
        byte[] bytes = Util.toBytes(name);
        if (bytes.length > maxNameLength()) {
            throw new FileSystemException(file.getPathForExceptionMessage(),
                null, "'" + name + "' is too big");
        }
        return bytes;
    }

    /**
     * @return the maximum supported length of xattr names (in bytes, including namespace)
     */
    protected abstract int maxNameLength();

    // Parses buffer as array of NULL-terminated C strings.
    private static List<String> asList(long address, int size, FileAttributeType attributeType) {
        List<String> list = new ArrayList<>();
        int start = 0;
        int pos = 0;
        if (attributeType == FileAttributeType.LINUX) {
            while (pos < size) {
                if (unsafe.getByte(address + pos) == 0) {
                    int len = pos - start;
                    byte[] value = new byte[len];
                    unsafe.copyMemory(null, address+start, value,
                        Unsafe.ARRAY_BYTE_BASE_OFFSET, len);
                    String s = Util.toString(value);
                    list.add(s);
                    start = pos + 1;
                }
                pos++;
            }
            // Only "user" namespace attributes
            list = list.stream()
                    .filter(s -> s.startsWith(USER_NAMESPACE))
                    .map(s -> s.substring(USER_NAMESPACE.length()))
                    .toList();
        } else { // FileAttributeType.BSD
            while (pos < size) {
                int len = unsafe.getByte(address + pos) & 0xFF;
                start = pos + 1;
                byte[] value = new byte[len];
                unsafe.copyMemory(null, address+start, value,
                    Unsafe.ARRAY_BYTE_BASE_OFFSET, len);
                String s = Util.toString(value);
                list.add(s);
                pos = start + len;
            }
        }

        return list;
    }

    // runs flistxattr, increases buffer size up to MAX_LISTXATTR_BUF_SIZE if required
    private static List<String> list(int fd, int bufSize, FileAttributeType attributeType) throws UnixException {
        try {
            try (NativeBuffer buffer = NativeBuffers.getNativeBuffer(bufSize)) {
                int n = flistxattr(fd, buffer.address(), bufSize);
                return asList(buffer.address(), n, attributeType);
            } // release buffer before recursion
        } catch (UnixException x) {
            if (x.errno() == ERANGE && bufSize < MAX_LISTXATTR_BUF_SIZE) {
                return list(fd, bufSize * 2, attributeType); // try larger buffer size:
            } else {
                throw x;
            }
        }
    }

    @Override
    public List<String> list() throws IOException  {
        int fd = -1;
        try {
            fd = file.openForAttributeAccess(followLinks);
        } catch (UnixException x) {
            x.rethrowAsIOException(file);
        }
        try {
            return list(fd, MIN_LISTXATTR_BUF_SIZE, attributeType);
        } catch (UnixException x) {
            throw new FileSystemException(file.getPathForExceptionMessage(),
                null, "Unable to get list of extended attributes: " +
                x.getMessage());
        } finally {
            close(fd, e -> null);
        }
    }

    @Override
    public int size(String name) throws IOException  {
        int fd = -1;
        try {
            fd = file.openForAttributeAccess(followLinks);
        } catch (UnixException x) {
            x.rethrowAsIOException(file);
        }
        try {
            // fgetxattr returns size if called with size==0
            return fgetxattr(fd, nameAsBytes(file,name), 0L, 0);
        } catch (UnixException x) {
            throw new FileSystemException(file.getPathForExceptionMessage(),
                null, "Unable to get size of extended attribute '" + name +
                "': " + x.getMessage());
        } finally {
            close(fd, e -> null);
        }
    }

    @Override
    public int read(String name, ByteBuffer dst) throws IOException {
        if (dst.isReadOnly())
            throw new IllegalArgumentException("Read-only buffer");
        int pos = dst.position();
        int lim = dst.limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);

        if (dst instanceof sun.nio.ch.DirectBuffer ddst) {
            NIO_ACCESS.acquireSession(dst);
            try {
                long address = ddst.address() + pos;
                int n = read(name, address, rem);
                dst.position(pos + n);
                return n;
            } finally {
                NIO_ACCESS.releaseSession(dst);
            }
        } else {
            try (NativeBuffer nb = NativeBuffers.getNativeBuffer(rem)) {
                long address = nb.address();
                int n = read(name, address, rem);

                // copy from buffer into backing array
                long off = dst.arrayOffset() + pos + (long) Unsafe.ARRAY_BYTE_BASE_OFFSET;
                unsafe.copyMemory(null, address, dst.array(), off, n);
                dst.position(pos + n);

                return n;
            }
        }
    }

    private int read(String name, long address, int rem) throws IOException {
        int fd = -1;
        try {
            fd = file.openForAttributeAccess(followLinks);
        } catch (UnixException x) {
            x.rethrowAsIOException(file);
        }
        try {
            int n = fgetxattr(fd, nameAsBytes(file, name), address, rem);

            // if remaining is zero then fgetxattr returns the size
            if (rem == 0) {
                if (n > 0)
                    throw new UnixException(ERANGE);
                return 0;
            }
            return n;
        } catch (UnixException x) {
            String msg = (x.errno() == ERANGE) ?
                    "Insufficient space in buffer" : x.getMessage();
            throw new FileSystemException(file.getPathForExceptionMessage(),
                    null, "Error reading extended attribute '" + name + "': " + msg);
        } finally {
            close(fd, e -> null);
        }
    }

    @Override
    public int write(String name, ByteBuffer src) throws IOException {
        int pos = src.position();
        int lim = src.limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);

        if (src instanceof sun.nio.ch.DirectBuffer buf) {
            NIO_ACCESS.acquireSession(src);
            try {
                long address = buf.address() + pos;
                write(name, address, rem);
                src.position(pos + rem);
                return rem;
            } finally {
                NIO_ACCESS.releaseSession(src);
            }
        } else {
            try (NativeBuffer nb = NativeBuffers.getNativeBuffer(rem)) {
                long address = nb.address();

                if (src.hasArray()) {
                    // copy from backing array into buffer
                    long off = src.arrayOffset() + pos + (long) Unsafe.ARRAY_BYTE_BASE_OFFSET;
                    unsafe.copyMemory(src.array(), off, null, address, rem);
                } else {
                    // backing array not accessible so transfer via temporary array
                    byte[] tmp = new byte[rem];
                    src.get(tmp);
                    src.position(pos);  // reset position as write may fail
                    unsafe.copyMemory(tmp, Unsafe.ARRAY_BYTE_BASE_OFFSET, null,
                            address, rem);
                }

                write(name, address, rem);
                src.position(pos + rem);
                return rem;
            }
        }
    }

    private void write(String name, long address, int rem) throws IOException {
        int fd = -1;
        try {
            fd = file.openForAttributeAccess(followLinks);
        } catch (UnixException x) {
            x.rethrowAsIOException(file);
        }
        try {
            fsetxattr(fd, nameAsBytes(file,name), address, rem);
        } catch (UnixException x) {
            throw new FileSystemException(file.getPathForExceptionMessage(),
                    null, "Error writing extended attribute '" + name + "': " +
                    x.getMessage());
        } finally {
            close(fd, e -> null);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        int fd = -1;
        try {
            fd = file.openForAttributeAccess(followLinks);
        } catch (UnixException x) {
            x.rethrowAsIOException(file);
        }
        try {
            fremovexattr(fd, nameAsBytes(file,name));
        } catch (UnixException x) {
            throw new FileSystemException(file.getPathForExceptionMessage(),
                null, "Unable to delete extended attribute '" + name + "': " + x.getMessage());
        } finally {
            close(fd, e -> null);
        }
    }

    /**
     * Used by copyTo/moveTo to copy extended attributes from source to target.
     *
     * @param   ofd
     *          file descriptor for source file
     * @param   nfd
     *          file descriptor for target file
     */
    static void copyExtendedAttributes(int ofd, int nfd) {
        copyExtendedAttributes(ofd, nfd, FileAttributeType.LINUX);
    }

    static void copyExtendedAttributes(int ofd, int nfd, FileAttributeType attributeType) {
        try {
            List<String> attrNames = list(ofd, MIN_LISTXATTR_BUF_SIZE, attributeType);
            for (String name : attrNames) {
                try {
                    copyExtendedAttribute(ofd, Util.toBytes(name), nfd);
                } catch(UnixException ignore){
                    // ignore
                }
            }
        } catch (UnixException e) {
            // unable to get list of attributes
            return;
        }
    }

    private static void copyExtendedAttribute(int ofd, byte[] name, int nfd)
        throws UnixException
    {
        int size = fgetxattr(ofd, name, 0L, 0);
        try (NativeBuffer buffer = NativeBuffers.getNativeBuffer(size)) {
            long address = buffer.address();
            size = fgetxattr(ofd, name, address, size);
            fsetxattr(nfd, name, address, size);
        }
    }
}
