/*
 * Copyright (c) 2008, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import jdk.internal.misc.Unsafe;

class BsdUserDefinedFileAttributeView
    extends UnixUserDefinedFileAttributeView
{
    BsdUserDefinedFileAttributeView(UnixPath file, boolean followLinks) {
        super(file, followLinks);
    }

    // Should be equal to EXTATTR_MAXNAMELEN
    @Override
    protected int maxNameLength() {
        return 255;
    }

    @Override
    protected boolean prefixNamespace() {
        return false;
    }

    protected static List<String> asList(long address, int size) {
        List<String> list = new ArrayList<>();
        int start = 0;
        int pos = 0;

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

        return list;
    }
}
