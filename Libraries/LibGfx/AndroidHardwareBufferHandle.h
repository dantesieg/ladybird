/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/Error.h>
#include <AK/Noncopyable.h>
#include <LibGfx/Size.h>
#include <LibIPC/Forward.h>

struct AHardwareBuffer;

namespace Gfx {

// A reference to an AHardwareBuffer, the memory that Android shares between processes and the GPU. Its pixels are
// R8G8B8A8_UNORM with premultiplied alpha.
class AndroidHardwareBufferHandle {
    AK_MAKE_NONCOPYABLE(AndroidHardwareBufferHandle);

public:
    // A buffer that the GPU renders into, and that another process samples from.
    static ErrorOr<AndroidHardwareBufferHandle> create(IntSize);

    // Takes over a reference that the caller holds.
    static AndroidHardwareBufferHandle adopt(AHardwareBuffer*);

    AndroidHardwareBufferHandle(AndroidHardwareBufferHandle&&);
    AndroidHardwareBufferHandle& operator=(AndroidHardwareBufferHandle&&);
    ~AndroidHardwareBufferHandle();

    AndroidHardwareBufferHandle clone() const;

    AHardwareBuffer* buffer() const { return m_buffer; }
    IntSize size() const;

private:
    explicit AndroidHardwareBufferHandle(AHardwareBuffer*);

    AHardwareBuffer* m_buffer { nullptr };
};

}

namespace IPC {

template<>
ErrorOr<void> encode(Encoder&, Gfx::AndroidHardwareBufferHandle const&);

template<>
ErrorOr<Gfx::AndroidHardwareBufferHandle> decode(Decoder&);

}
