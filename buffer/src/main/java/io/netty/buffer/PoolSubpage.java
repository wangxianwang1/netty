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

final class PoolSubpage<T> implements PoolSubpageMetric {

    /***
     * 所属的PoolChunk
     */
    final PoolChunk<T> chunk;
    /***
     * 在PoolChunk中的memoryMap中的节点编号
     */
    private final int memoryMapIdx;

    /***
     * 在Chunk中的偏移字节量
     */
    private final int runOffset;

    /***
     * Page的大小PoolChunk中的pageSize的大小
     */
    private final int pageSize;

    /***
     * Subpage分配信息数组
     */
    /**
     * Subpage 分配信息数组
     *
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     *   例如：Page 默认大小为 8KB ，Subpage 默认最小为 16B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     *        因此，bitmap 数组大小为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     *    为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     */
    private final long[] bitmap;

    /***
     * 上一个节点
     */
    PoolSubpage<T> prev;

    /***
     * 下一个节点
     */
    PoolSubpage<T> next;

    /***
     * 是否未销毁
     */
    boolean doNotDestroy;

    /***
     * 每个subpage的占用内存的大小
     */
    int elemSize;

    /***
     * 总共的subpage的数量
     */
    private int maxNumElems;

    /***
     * bitmap的长度
     */
    private int bitmapLength;

    /***
     * 下一个可分配 Subpage 的数组位置
     */
    private int nextAvail;

    /***
     * 剩余可用 Subpage 的数量
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    /***
     * 双向节点 头节点
     * @param pageSize
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /***
     * 双向节点  Page节点
     * @param head
     * @param chunk
     * @param memoryMapIdx
     * @param runOffset
     * @param pageSize
     * @param elemSize
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        /***
         * 创建bitmap数组
         * pageSize >>> 10最后得到的值为8
         * 至于为什么是8看下bitmap属性的注释
         */
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    /***
     * 初始化
     * @param head
     * @param elemSize
     */
    void init(PoolSubpage<T> head, int elemSize) {
        /***
         * 未销毁
         */
        doNotDestroy = true;
        /***
         * 初始化elemSize
         * 默认为256
         */
        this.elemSize = elemSize;
        if (elemSize != 0) {
            /***
             * 初始化maxNumElems
             */
            maxNumElems = numAvail = pageSize / elemSize;
            /***
             * 初始化nextAvail
             */
            nextAvail = 0;
            /***
             * 计算bitmapLength的大小
             */
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {  //未整除  补1
                bitmapLength ++;
            }

            /***
             * 初始化bitmap
             */
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        /***
         * 添加到Arena的双向链表中
         */
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    /***
     * 分配一个Subpage内存快 并返回该内存快的位置handle
     * @return
     */
    long allocate() {
        /***
         * 这种case是不存在的
         */
        if (elemSize == 0) {
            return toHandle(0);
        }

        /***
         * 可用数量为0等原因导致的不可分配
         */
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        /***
         * 获取下一个可用的Subpage在bitmap的总体的位置
         */
        final int bitmapIdx = getNextAvail();
        /***
         * 获得下一个可用的subpage在biymap中的数组的位置
         */
        int q = bitmapIdx >>> 6;
        /***
         * 获取下一个可用的Subpage在bitmap中数组的位置的第几bits
         */
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        /***
         * 修改subpage在bitmap中不可分配
         */
        bitmap[q] |= 1L << r;

        /***
         * 可用的Subpage内存快计数减一
         */
        if (-- numAvail == 0) {
            /***
             * 从双向链表中移除
             */
            removeFromPool();
        }

        /***
         * 计算handle
         */
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        /***
         * 防治性的编程 现实中不存在
         */
        if (elemSize == 0) {
            return true;
        }
        /***
         * 获取Subpage在bitmap中的数组的位置
         */
        int q = bitmapIdx >>> 6;
        /***
         *  获得 Subpage 在 bitmap 中数组的位置的第几 bits
         */
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 修改 Subpage 在 bitmap 中可分配。
        bitmap[q] ^= 1L << r;

        /***
         * 设置下一个可用为当前 Subpage
         */
        setNextAvail(bitmapIdx);

        /***
         *  可用 Subpage 内存块的计数加一
         */
        if (numAvail ++ == 0) {
            /***
             * 添加到 Arena 的双向链表中。
             */
            addToPool(head);
            return true;
        }

        /***
         *  还有 Subpage 在使用
         */
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            /***
             * 双向链表中，只有该节点，不进行移除
             */
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /***
     * 添加到 Arena 的双向链表中。
     * @param head
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        /***
         * 将当前节点插入到head和head.next中间
         */
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        /***
         * 前后节点 互相指向
         */
        prev.next = next;
        next.prev = prev;
        /***
         * 当前节点置空
         */
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
