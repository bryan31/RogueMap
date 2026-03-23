package com.yomahub.roguemap.serialization;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * 泛型类型引用，用于在运行时保留复杂泛型信息。
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * TypeReference<List<User>> typeRef = new TypeReference<List<User>>() {};
 * }</pre>
 *
 * @param <T> 引用的目标类型
 */
public abstract class TypeReference<T> {

    private final Type type;
    private final Class<?> rawClass;

    protected TypeReference() {
        Type superType = getClass().getGenericSuperclass();
        if (!(superType instanceof ParameterizedType)) {
            throw new IllegalStateException("TypeReference 必须使用匿名子类声明，例如 new TypeReference<List<User>>() {}");
        }

        this.type = ((ParameterizedType) superType).getActualTypeArguments()[0];
        this.rawClass = resolveRawClass(type);
    }

    public Type getType() {
        return type;
    }

    public Class<?> getRawClass() {
        return rawClass;
    }

    private static Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return resolveRawClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = resolveRawClass(((GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : resolveRawClass(bounds[0]);
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return upperBounds.length == 0 ? Object.class : resolveRawClass(upperBounds[0]);
        }
        throw new IllegalArgumentException("无法解析原始类型: " + type);
    }

    @Override
    public String toString() {
        return type.toString();
    }
}
