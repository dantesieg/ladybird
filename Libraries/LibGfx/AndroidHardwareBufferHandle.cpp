/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include <LibCore/System.h>
#include <LibGfx/AndroidHardwareBufferHandle.h>
#include <LibIPC/Decoder.h>
#include <LibIPC/Encoder.h>
#include <LibIPC/File.h>

#include <android/hardware_buffer.h>
#include <sys/socket.h>

namespace Gfx {

ErrorOr<AndroidHardwareBufferHandle> AndroidHardwareBufferHandle::create(IntSize size)
{
    if (size.is_empty())
        return Error::from_string_literal("Cannot create an empty hardware buffer");

    AHardwareBuffer_Desc description {};
    description.width = static_cast<u32>(size.width());
    description.height = static_cast<u32>(size.height());
    description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    description.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;

    AHardwareBuffer* buffer = nullptr;
    if (auto result = AHardwareBuffer_allocate(&description, &buffer); result != 0)
        return Error::from_errno(-result);
    return AndroidHardwareBufferHandle { buffer };
}

AndroidHardwareBufferHandle AndroidHardwareBufferHandle::adopt(AHardwareBuffer* buffer)
{
    VERIFY(buffer);
    return AndroidHardwareBufferHandle { buffer };
}

AndroidHardwareBufferHandle::AndroidHardwareBufferHandle(AHardwareBuffer* buffer)
    : m_buffer(buffer)
{
}

AndroidHardwareBufferHandle::AndroidHardwareBufferHandle(AndroidHardwareBufferHandle&& other)
    : m_buffer(exchange(other.m_buffer, nullptr))
{
}

AndroidHardwareBufferHandle& AndroidHardwareBufferHandle::operator=(AndroidHardwareBufferHandle&& other)
{
    if (this != &other) {
        if (m_buffer)
            AHardwareBuffer_release(m_buffer);
        m_buffer = exchange(other.m_buffer, nullptr);
    }
    return *this;
}

AndroidHardwareBufferHandle::~AndroidHardwareBufferHandle()
{
    if (m_buffer)
        AHardwareBuffer_release(m_buffer);
}

AndroidHardwareBufferHandle AndroidHardwareBufferHandle::clone() const
{
    VERIFY(m_buffer);
    AHardwareBuffer_acquire(m_buffer);
    return AndroidHardwareBufferHandle { m_buffer };
}

IntSize AndroidHardwareBufferHandle::size() const
{
    VERIFY(m_buffer);
    AHardwareBuffer_Desc description {};
    AHardwareBuffer_describe(m_buffer, &description);
    return { static_cast<int>(description.width), static_cast<int>(description.height) };
}

}

namespace IPC {

// NB: Android only hands a hardware buffer to another process through a socket of its own, so each one travels over a
//     fresh socket pair: its handle is written into one end, and the other end goes along with the message.
template<>
ErrorOr<void> encode(Encoder& encoder, Gfx::AndroidHardwareBufferHandle const& handle)
{
    int sockets[2] {};
    TRY(Core::System::socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
    auto receiving_end = File::adopt_fd(sockets[1]);
    auto result = AHardwareBuffer_sendHandleToUnixSocket(handle.buffer(), sockets[0]);
    (void)Core::System::close(sockets[0]);
    if (result != 0)
        return Error::from_errno(-result);
    TRY(encoder.encode(receiving_end));
    return {};
}

template<>
ErrorOr<Gfx::AndroidHardwareBufferHandle> decode(Decoder& decoder)
{
    auto receiving_end = TRY(decoder.decode<File>());
    AHardwareBuffer* buffer = nullptr;
    if (auto result = AHardwareBuffer_recvHandleFromUnixSocket(receiving_end.fd(), &buffer); result != 0)
        return Error::from_errno(-result);
    return Gfx::AndroidHardwareBufferHandle::adopt(buffer);
}

}
