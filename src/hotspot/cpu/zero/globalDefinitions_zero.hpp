/*
 * Copyright (c) 1997, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2009, 2021, Red Hat, Inc.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 */

#ifndef CPU_ZERO_GLOBALDEFINITIONS_ZERO_HPP
#define CPU_ZERO_GLOBALDEFINITIONS_ZERO_HPP

#ifdef _LP64
#define SUPPORTS_NATIVE_CX8
#endif

// The expected size in bytes of a cache line.
#define DEFAULT_CACHE_LINE_SIZE 64

// The default padding size for data structures to avoid false sharing.
#define DEFAULT_PADDING_SIZE DEFAULT_CACHE_LINE_SIZE

#include <ffi.h>

#include <ffi.h>

// Indicates whether the C calling conventions require that
// 32-bit integer argument values are extended to 64 bits.
const bool CCallingConventionRequiresIntsAsLongs = false;
#if defined(AIX)
const size_t pd_segfault_address = -1;
#elif defined(S390)
const size_t pd_segfault_address = 4096;
#else
const size_t pd_segfault_address = 1024;
#endif

#endif // CPU_ZERO_GLOBALDEFINITIONS_ZERO_HPP
