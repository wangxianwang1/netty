/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /***
     * 用户回收对象
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    /***
     * Chunk对象
     */
    protected PoolChunk<T> chunk;

    /***
     * 从Chunk对象中分配的内存快所处的位置
     */
    protected long handle;
    /***
     * 内存空间
     */
    protected T memory;

    /****
     * 开始位置
     */
    protected int offset;

    /***
     * 容量
     */
    protected int length;

    /****
     * 占用内存
     */
    int maxLength;

    /****
     * Chunk
     */
    PoolThreadCache cache;
    /***
     * 临时的ByteBuff对象
     */
    ByteBuffer tmpNioBuf;

    /***
     * 分配器
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) {
        /***
         * 设置最大的容量
         */
        maxCapacity(maxCapacity);

        /***
         * 设置引用数量为0
         */
        setRefCnt(1);
        /***
         * 重置读写索引为0
         */
        setIndex0(0, 0);
        /***
         * 重置读写标记位
         */
        discardMarks();
    }

    /***
     * 是当前的容量值
     * maxCapacity才是最大的容量值
     * 那么，maxLength 属性有什么用？表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )。
     * 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity 。
     * @return
     */
    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        /***
         * 检查新的容量  不能超过最大容量
         */
        checkNewCapacity(newCapacity);

        /***
         *  Chunk内存 非池化
         */
        // If the request capacity does not require reallocation, just update the length of the memory.
        if (chunk.unpooled) {
            if (newCapacity == length) {  //相等无需用扩容
                return this;
            }
        }

        /***
         * Chunk内存  池化
         */
        else {
            /***
             * 扩容
             */
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            }
            /***
             * 缩容
             */
            else if (newCapacity < length) {
                //大于 maxLength的一半
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        // 因为 Netty SubPage 最小是 16 ，如果小于等 16 ，无法缩容。
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            // 设置读写索引，避免超过最大容量
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        // 设置读写索引，避免超过最大容量
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                return this;
            }
        }

        /***
         * 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间。
         */
        // Reallocation required.
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    /***
     * 创建临时的ByteBuffer
     * @return
     */
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    /***
     * 当引用计数为0时  调用该方法进行内存回收
     */
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        return offset + index;
    }
}
