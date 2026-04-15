package com.yomahub.roguemap.memory;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * UUID ↔ int ordinal 双向映射注册表。
 * 已释放的 ordinal 进入 freeList 供下次注册复用，保证 idTable 不无限增长。
 */
public class OrdinalRegistry {

    private String[] idTable;
    private final Map<String, Integer> idToOrdinal = new HashMap<>();
    private int[] freeList;
    private int freeTop = 0;
    private int nextOrdinal = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public OrdinalRegistry() {
        idTable = new String[1024];
        freeList = new int[256];
    }

    /** 注册 uuid，返回分配的 ordinal（优先复用已释放的） */
    public int register(String uuid) {
        lock.writeLock().lock();
        try {
            // Critical 1: if already registered, return existing ordinal without allocating a new one
            Integer existing = idToOrdinal.get(uuid);
            if (existing != null) return existing;
            int ordinal;
            if (freeTop > 0) {
                ordinal = freeList[--freeTop];
            } else {
                ordinal = nextOrdinal++;
                if (ordinal >= idTable.length) {
                    idTable = Arrays.copyOf(idTable, idTable.length * 2);
                }
            }
            idTable[ordinal] = uuid;
            idToOrdinal.put(uuid, ordinal);
            return ordinal;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 释放 uuid 对应的 ordinal，放入 freeList */
    public void release(String uuid) {
        lock.writeLock().lock();
        try {
            Integer ordinal = idToOrdinal.remove(uuid);
            if (ordinal == null) return;
            idTable[ordinal] = null;
            if (freeTop >= freeList.length) {
                freeList = Arrays.copyOf(freeList, freeList.length * 2);
            }
            freeList[freeTop++] = ordinal;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 正查：uuid → ordinal；未注册返回 -1 */
    public int getOrdinal(String uuid) {
        lock.readLock().lock();
        try {
            Integer v = idToOrdinal.get(uuid);
            return v == null ? -1 : v;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 反查：ordinal → uuid；无效返回 null */
    public String getId(int ordinal) {
        lock.readLock().lock();
        try {
            if (ordinal < 0 || ordinal >= nextOrdinal) return null;
            return idTable[ordinal];
        } finally {
            lock.readLock().unlock();
        }
    }

    /** nextOrdinal（包含已释放的槽位），用于预分配数组大小 */
    public int capacity() {
        lock.readLock().lock();
        try { return nextOrdinal; } finally { lock.readLock().unlock(); }
    }

    /** 序列化为字节数组，仅写入活跃条目。格式：[count:4B]([ordinal:4B][id_len:2B][id UTF-8 bytes])* */
    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            int count = idToOrdinal.size();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + count * 40);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(count);
            for (int i = 0; i < nextOrdinal; i++) {
                if (idTable[i] == null) continue;
                dos.writeInt(i);
                byte[] idBytes = idTable[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeShort(idBytes.length);
                dos.write(idBytes);
            }
            dos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 从字节数组反序列化。兼容旧格式（每条目固定16字节UUID）和新格式（变长字符串）。 */
    public static OrdinalRegistry deserialize(byte[] data) throws IOException {
        OrdinalRegistry reg = new OrdinalRegistry();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int count = dis.readInt();
        // Legacy format: [count:4B]([ordinal:4B][msb:8B][lsb:8B])* => total = 4 + count * 20 bytes
        // New format:    [count:4B]([ordinal:4B][id_len:2B][id UTF-8 bytes])* => variable length
        boolean legacyFormat = (data.length == 4 + count * 20);
        int maxOrdinal = -1;
        for (int i = 0; i < count; i++) {
            int ordinal = dis.readInt();
            String id;
            if (legacyFormat) {
                long msb = dis.readLong();
                long lsb = dis.readLong();
                id = new UUID(msb, lsb).toString();
            } else {
                int idLen = dis.readShort() & 0xFFFF;
                byte[] idBytes = new byte[idLen];
                dis.readFully(idBytes);
                id = new String(idBytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (ordinal >= reg.idTable.length) {
                reg.idTable = Arrays.copyOf(reg.idTable, Math.max(ordinal + 1, reg.idTable.length * 2));
            }
            reg.idTable[ordinal] = id;
            reg.idToOrdinal.put(id, ordinal);
            if (ordinal > maxOrdinal) maxOrdinal = ordinal;
        }
        reg.nextOrdinal = maxOrdinal + 1;
        // Critical 2: rebuild freeList from gaps in idTable
        for (int i = 0; i < reg.nextOrdinal; i++) {
            if (reg.idTable[i] == null) {
                if (reg.freeTop >= reg.freeList.length) {
                    reg.freeList = Arrays.copyOf(reg.freeList, reg.freeList.length * 2);
                }
                reg.freeList[reg.freeTop++] = i;
            }
        }
        return reg;
    }
}
