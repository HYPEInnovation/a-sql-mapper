package com.ajjpj.asqlmapper.mapper.beans.javatypes;

import com.ajjpj.acollections.immutable.AVector;
import com.ajjpj.acollections.util.AOption;
import com.ajjpj.asqlmapper.core.PrimitiveTypeRegistry;
import com.ajjpj.asqlmapper.mapper.annotations.Column;
import com.ajjpj.asqlmapper.mapper.annotations.Ignore;
import com.ajjpj.asqlmapper.mapper.beans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.schema.ColumnMetaData;
import com.ajjpj.asqlmapper.mapper.schema.TableMetaData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.ajjpj.acollections.mutable.AMutableArrayWrapper.wrap;
import static com.ajjpj.acollections.util.AUnchecker.*;


public class ImmutableWithBuilderMetaDataExtractor implements BeanMetaDataExtractor {
    @Override public List<BeanProperty> beanProperties (Connection conn, Class<?> beanType, TableMetaData tableMetaData, PrimitiveTypeRegistry primTypes) {
        return executeUnchecked(() -> {
            final AVector<Method> getters = wrap(beanType.getMethods())
                    .filterNot(m -> m.getName().equals("hashCode") || m.getName().equals("toString"))
                    .filter(m -> m.getParameterCount() == 0 && (m.getModifiers() & Modifier.STATIC) == 0 && primTypes.isPrimitiveType(m.getReturnType()))
                    .toVector();

            final Class<?> builderClass = builderFactoryFor(beanType).get().getClass();

            return getters.flatMap(getter -> executeUnchecked(() -> {
                final Method setter = beanType.getMethod("with" + toFirstUpper(getter.getName()), getter.getReturnType());
                final Method builderSetter = builderClass.getMethod(getter.getName(), getter.getReturnType());

                final Ignore getterIgnore = getter.getAnnotation(Ignore.class);

                if (getterIgnore != null && getterIgnore.value())
                    return AOption.empty();

                final Column columnAnnot = getter.getAnnotation(Column.class);
                final String columnName = AOption.of(columnAnnot).map(Column::value).orElse(getter.getName());
                final ColumnMetaData columnMetaData = tableMetaData.findColByName(columnName)
                        .orElseThrow(() -> new IllegalArgumentException("no database column " + tableMetaData.tableName + "." + columnName + " for property " + getter.getName() + " of bean " + beanType.getName()));

                return AOption.some(new BeanProperty(getter.getReturnType(), columnMetaData, getter, setter, true, builderSetter));
            }));


        });
    }

    private String toFirstUpper(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override public Supplier<Object> builderFactoryFor (Class<?> beanType) {
        return executeUnchecked(() -> {
            final Method mtd = beanType.getMethod("builder");
            return () -> executeUnchecked(() -> mtd.invoke(null));
        });
    }

    @Override public Function<Object, Object> builderFinalizerFor (Class<?> beanType) {
        return executeUnchecked(() -> {
            final Class<?> builderClass = builderFactoryFor(beanType).get().getClass();
            final Method mtd = builderClass.getMethod("build");

            return builder -> executeUnchecked(() -> mtd.invoke(builder));
        });
    }
}