package com.ajjpj.asqlmapper.core.provided;

import com.ajjpj.acollections.AMap;
import com.ajjpj.acollections.util.AOption;


public interface ProvidedProperties {
    static ProvidedProperties empty() {
        return new ProvidedPropertiesImpl(AMap.empty(), AMap.empty());
    }

    static ProvidedProperties of (String propertyName, String referencedPropertyName, ProvidedValues propertyValues) {
        return empty().with(propertyName, referencedPropertyName, propertyValues);
    }

    boolean hasValuesFor(String name);
    Class<?> pkType(String name);
    String referencedPropertyNameFor (String name);

    AOption<Object> get(String name, Object pk);

    ProvidedProperties with (String propertyName, String referencedPropertyName, ProvidedValues providedValues);

    boolean nonEmpty ();

}
