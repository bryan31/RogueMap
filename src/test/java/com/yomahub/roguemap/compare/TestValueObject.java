package com.yomahub.roguemap.compare;

import java.io.Serializable;
import java.util.Objects;

/**
 * 测试用的值对象，包含10个常见类型的字段
 */
public class TestValueObject implements Serializable {
    private static final long serialVersionUID = 1L;

    // 1. int 类型
    private int id;

    // 2. long 类型
    private long timestamp;

    // 3. double 类型
    private double price;

    // 4. boolean 类型
    private boolean active;

    // 5. String 类型
    private String name;

    // 6. String 类型
    private String description;

    // 7. byte 类型
    private byte status;

    // 8. short 类型
    private short quantity;

    // 9. float 类型
    private float rating;

    // 10. char 类型
    private char category;

    public TestValueObject() {
    }

    public TestValueObject(int id, long timestamp, double price, boolean active,
                          String name, String description, byte status,
                          short quantity, float rating, char category) {
        this.id = id;
        this.timestamp = timestamp;
        this.price = price;
        this.active = active;
        this.name = name;
        this.description = description;
        this.status = status;
        this.quantity = quantity;
        this.rating = rating;
        this.category = category;
    }

    // Getters
    public int getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public byte getStatus() {
        return status;
    }

    public short getQuantity() {
        return quantity;
    }

    public float getRating() {
        return rating;
    }

    public char getCategory() {
        return category;
    }

    // Setters
    public void setId(int id) {
        this.id = id;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStatus(byte status) {
        this.status = status;
    }

    public void setQuantity(short quantity) {
        this.quantity = quantity;
    }

    public void setRating(float rating) {
        this.rating = rating;
    }

    public void setCategory(char category) {
        this.category = category;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestValueObject that = (TestValueObject) o;
        return id == that.id &&
                timestamp == that.timestamp &&
                Double.compare(that.price, price) == 0 &&
                active == that.active &&
                status == that.status &&
                quantity == that.quantity &&
                Float.compare(that.rating, rating) == 0 &&
                category == that.category &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, timestamp, price, active, name, description,
                status, quantity, rating, category);
    }

    @Override
    public String toString() {
        return "TestValueObject{" +
                "id=" + id +
                ", timestamp=" + timestamp +
                ", price=" + price +
                ", active=" + active +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", status=" + status +
                ", quantity=" + quantity +
                ", rating=" + rating +
                ", category=" + category +
                '}';
    }

    /**
     * 估算对象的大小（字节）
     */
    public long estimateSize() {
        long size = 0;
        size += 4;  // int id
        size += 8;  // long timestamp
        size += 8;  // double price
        size += 1;  // boolean active
        size += (name != null ? name.length() * 2 : 0);  // String name (UTF-16)
        size += (description != null ? description.length() * 2 : 0);  // String description (UTF-16)
        size += 1;  // byte status
        size += 2;  // short quantity
        size += 4;  // float rating
        size += 2;  // char category
        size += 16; // 对象头开销（大约）
        return size;
    }
}
