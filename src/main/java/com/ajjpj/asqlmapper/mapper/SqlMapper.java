package com.ajjpj.asqlmapper.mapper;

import static com.ajjpj.acollections.util.AUnchecker.executeUnchecked;
import static com.ajjpj.asqlmapper.core.SqlSnippet.*;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ajjpj.acollections.AList;
import com.ajjpj.acollections.immutable.AVector;
import com.ajjpj.acollections.mutable.AMutableListWrapper;
import com.ajjpj.acollections.util.AOption;
import com.ajjpj.asqlmapper.core.SqlBuilder;
import com.ajjpj.asqlmapper.core.SqlEngine;
import com.ajjpj.asqlmapper.core.SqlSnippet;
import com.ajjpj.asqlmapper.core.injectedproperties.InjectedProperty;
import com.ajjpj.asqlmapper.javabeans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.beans.BeanMapping;
import com.ajjpj.asqlmapper.mapper.beans.BeanMappingRegistry;
import com.ajjpj.asqlmapper.mapper.beans.relations.ManyToManySpec;
import com.ajjpj.asqlmapper.mapper.beans.relations.OneToManySpec;
import com.ajjpj.asqlmapper.mapper.beans.relations.ToOneSpec;
import com.ajjpj.asqlmapper.mapper.beans.tablename.TableNameExtractor;
import com.ajjpj.asqlmapper.mapper.injectedproperties.MappedManyToMany;
import com.ajjpj.asqlmapper.mapper.injectedproperties.MappedOneToMany;
import com.ajjpj.asqlmapper.mapper.injectedproperties.MappedToOne;
import com.ajjpj.asqlmapper.mapper.schema.SchemaRegistry;

public class SqlMapper {
    private final SqlEngine sqlEngine;
    private final BeanMappingRegistry mappingRegistry;
    private final SchemaRegistry schemaRegistry;
    private final TableNameExtractor tableNameExtractor;

    public SqlMapper(SqlEngine sqlEngine, BeanMappingRegistry mappingRegistry, SchemaRegistry schemaRegistry, TableNameExtractor tableNameExtractor) {
        this.schemaRegistry = schemaRegistry;
        this.tableNameExtractor = tableNameExtractor;
        this.sqlEngine = sqlEngine.withRowExtractor(mappingRegistry.metaDataRegistry().asRowExtractor());
        this.mappingRegistry = mappingRegistry;
    }

    public SqlEngine engine() {
        return sqlEngine;
    }

    public BeanMappingRegistry getBeanMappingRegistry() {
        return mappingRegistry;
    }

    public SqlSnippet tableName(Class<?> beanType) {
        return tableName(engine().defaultConnection(), beanType);
    }
    public SqlSnippet tableName(Connection conn, Class<?> beanType) {
        return sql(mappingRegistry.getBeanMapping(conn, beanType).tableName());
    }

    public <T> AMapperQuery<T> query(Class<T> beanType, SqlSnippet sql, SqlSnippet... moreSql) {
        return new AMapperQueryImpl<>(this, beanType, concat(sql, moreSql), engine().primitiveTypeRegistry(), engine().rowExtractorFor(beanType),
                engine().listeners(), engine().defaultConnectionSupplier(), AVector.empty());
    }
    public <T> AMapperQuery<T> query(Class<T> beanType, String sql, Object... params) {
        return new AMapperQueryImpl<>(this, beanType, sql(sql, params), engine().primitiveTypeRegistry(), engine().rowExtractorFor(beanType),
                engine().listeners(), engine().defaultConnectionSupplier(), AVector.empty());
    }

    public MappedOneToMany oneToMany(String propertyName) {
        return new MappedOneToMany(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.empty());
    }
    public MappedOneToMany oneToMany(String propertyName, OneToManySpec spec) {
        return new MappedOneToMany(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.ofNullable(spec));
    }

    public MappedManyToMany manyToMany(String propertyName) {
        return new MappedManyToMany(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.empty());
    }
    public MappedManyToMany manyToMany(String propertyName, ManyToManySpec spec) {
        return new MappedManyToMany(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.ofNullable(spec));
    }

    public InjectedProperty toOne(String propertyName) {
        return new MappedToOne(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.empty());
    }
    public InjectedProperty toOne(String propertyName, ToOneSpec spec) {
        return new MappedToOne(propertyName, mappingRegistry, (cls, sql) -> query(cls, sql), Optional.ofNullable(spec));
    }

    public <T> AList<T> insertMany(List<T> os) {
        return insertMany(engine().defaultConnection(), os);
    }
    private <T> AList<T> insertMany(Connection conn, List<T> os) {
        if (os.isEmpty()) {
            return AList.empty();
        }

        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, os.get(0).getClass());
        if (beanMapping.pkStrategy().isAutoIncrement()) {
            return insertManyAutoGenerated(conn, beanMapping, os);
        } else {
            return insertManyProvidingPk(conn, beanMapping, os);
        }
    }
    private <T> AVector<T> insertManyAutoGenerated(Connection conn, BeanMapping beanMapping, List<T> os) {
        return executeUnchecked(() -> {
            final BeanProperty pkProperty = beanMapping.pkProperty();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (T o : os) {
                if (beanMapping != mappingRegistry.getBeanMapping(conn, o.getClass())) {
                    throw new IllegalArgumentException("multi-insert only for beans of the same type");
                }
                appendInsertFragmentForElement(beanMapping, builder, first, o, false);
                first = false;
            }

            if (pkProperty != null) {
                final List<?> pkValues = sqlEngine.insertSingleColPkInCol(pkProperty.columnName(), pkProperty.propClass(), builder.build()).executeMulti(conn);
                if (pkValues.size() != os.size()) {
                    throw new IllegalStateException("inserting " + os.size() + " rows returned " + pkValues.size() + " - mismatch");
                }

                final AVector.Builder<T> result = AVector.builder();
                for (int i = 0; i < os.size(); i++) {
                    //noinspection unchecked
                    result.add((T) pkProperty.set(os.get(i), pkValues.get(i)));
                }
                return result.build();
            } else {
                sqlEngine.executeUpdate(conn, builder.build());
                return AVector.from(os);
            }
        });
    }

    private <T> void appendInsertFragmentForElement(BeanMapping beanMapping, SqlBuilder builder, boolean first, T o, boolean includePkColumn) {
        if (first) {
            builder.append(insertStatement(beanMapping, o, includePkColumn));
        } else {
            final AList<String> properties = includePkColumn ? beanMapping.mappedProperties() : beanMapping.mappedPropertiesWithoutPk();

            builder
                    .append(",(")
                    .append(params(properties.map(p -> beanMapping.beanProperty(p).get(o))))
                    .append(")")
            ;
        }
    }

    private <T> AVector<T> insertManyProvidingPk(Connection conn, BeanMapping beanMapping, List<T> os) {
        return executeUnchecked(() -> {
            final AVector.Builder<T> result = AVector.builder();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (Object withoutPk : os) {
                if (beanMapping != mappingRegistry.getBeanMapping(conn, withoutPk.getClass())) {
                    throw new IllegalArgumentException("multi-insert only for beans of the same type");
                }
                final AOption<Object> optPk = beanMapping.pkStrategy().newPrimaryKey(conn);
                final Object withPk = optPk.fold(withoutPk, (res, el) -> beanMapping.pkProperty().set(res, el));
                appendInsertFragmentForElement(beanMapping, builder, first, withPk, true);
                first = false;
            }

            sqlEngine.executeUpdate(conn, builder.build());
            return result.build();
        });
    }

    public <T> T insert(T o) {
        return insert(engine().defaultConnection(), o);
    }
    private <T> T insert(Connection conn, T o) {
        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, o.getClass());
        if (beanMapping.pkStrategy().isAutoIncrement()) {
            return insertAutoGenerated(conn, o, beanMapping);
        } else {
            return insertProvidingPk(conn, o, beanMapping);
        }
    }
    private <T> T insertAutoGenerated(Connection conn, T o, BeanMapping beanMapping) {
        return executeUnchecked(() -> {
            final SqlSnippet insertStmt = insertStatement(beanMapping, o, false);

            final BeanProperty pkProperty = beanMapping.pkProperty();
            final Object pkValue = sqlEngine.insertSingleColPkInCol(pkProperty.columnName(), pkProperty.propClass(), insertStmt).executeSingle(conn);
            //noinspection unchecked
            return (T) pkProperty.set(o, pkValue);
        });
    }
    private SqlSnippet insertStatement(BeanMapping beanMapping, Object bean, boolean withPk) {
        final AList<String> properties = withPk ? beanMapping.mappedProperties() : beanMapping.mappedPropertiesWithoutPk();

        final SqlSnippet into = commaSeparated(properties.map(p -> sql(beanMapping.beanProperty(p).columnName())));
        final SqlSnippet values = params(properties.map(p -> beanMapping.beanProperty(p).get(bean)));

        return concat(
                sql("INSERT INTO " + beanMapping.tableName() + "("),
                into,
                sql(") VALUES ("),
                values,
                sql(")")
        );
    }
    private <T> T insertProvidingPk(Connection conn, Object beanWithoutPk, BeanMapping beanMapping) {
        return executeUnchecked(() -> {
            final AOption<Object> optPk = beanMapping.pkStrategy().newPrimaryKey(conn);
            final Object beanWithPk = optPk.fold(beanWithoutPk, (res, el) -> beanMapping.pkProperty().set(res, el));

            final SqlSnippet insertStmt = insertStatement(beanMapping, beanWithPk, true);

            sqlEngine.executeUpdate(conn, insertStmt);
            //noinspection unchecked
            return (T) beanWithPk;
        });
    }

    public boolean update(Object bean) {
        return update(engine().defaultConnection(), bean);
    }

    private SqlSnippet updateSnippet(Connection conn, Object bean) {
        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, bean.getClass());

        final SqlSnippet updates = commaSeparated(
                beanMapping.mappedPropertiesWithoutPk()
                        .map(p -> sql(beanMapping.beanProperty(p).columnName() + "=?", beanMapping.beanProperty(p).get(bean)))
        );

        return concat(
                sql("UPDATE " + beanMapping.tableName() + " SET"),
                updates,
                sql("WHERE " + beanMapping.pkProperty().columnName() + "=?", beanMapping.pkProperty().get(bean))
        );
    }

    public boolean update(Connection conn, Object bean) {
        return sqlEngine.executeUpdate(conn, updateSnippet(conn, bean)) == 1;
    }

    /**
     * all beans must have the same type for JDBC batching to work
     */
    public List<Boolean> batchUpdate(List<Object> beans) {
        return batchUpdate(engine().defaultConnection(), beans);
    }
    /**
     * all beans must have the same type for JDBC batching to work
     */
    public List<Boolean> batchUpdate(Connection conn, List<Object> beans) {
        final List<SqlSnippet> snippets = AMutableListWrapper
                .wrap(beans)
                .map(b -> updateSnippet(conn, b));
        final int[] results = sqlEngine.executeBatch(conn, snippets);
        final List<Boolean> result = new ArrayList<>(results.length);
        for(int r: results) {
            result.add(r == 1);
        }
        return result;
    }

    public boolean delete(Object bean) {
        return delete(engine().defaultConnection(), bean);
    }
    private boolean delete(Connection conn, Object bean) {
        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, bean.getClass());
        final BeanProperty pkProperty = beanMapping.pkProperty();

        return executeUnchecked(() ->
                sqlEngine.executeUpdate(conn, "DELETE FROM " + beanMapping.tableName() + " WHERE " + pkProperty.columnName() + "=?", pkProperty.get(bean)) == 1
        );
    }
    public boolean delete(Class<?> beanType, Object pk) {
        return delete(engine().defaultConnection(), beanType, pk);
    }
    private boolean delete(Connection conn, Class<?> beanType, Object pk) {
        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, beanType);
        final BeanProperty pkProperty = beanMapping.pkProperty();

        return executeUnchecked(() ->
                sqlEngine.executeUpdate(conn, "DELETE FROM " + beanMapping.tableName() + " WHERE " + pkProperty.columnName() + "=?", pk) == 1
        );
    }

    public boolean patch(Class<?> beanType, Object pk, Map<String, Object> newValues) {
        return patch(engine().defaultConnection(), beanType, pk, newValues);
    }
    private boolean patch(Connection conn, Class<?> beanType, Object pk, Map<String, Object> newValues) {
        final BeanMapping beanMapping = mappingRegistry.getBeanMapping(conn, beanType);
        final BeanProperty pkProperty = beanMapping.pkProperty();

        //TODO to-one / foreign keys

        final SqlBuilder builder = new SqlBuilder();
        boolean first = true;

        builder.append("UPDATE " + beanMapping.tableName() + " SET");

        for (String propName : newValues.keySet()) {
            final AOption<String> optProp = beanMapping.mappedProperties().find(p -> p.equals(propName));
            if (optProp.isEmpty()) {
                continue;
            }

            final BeanProperty prop = beanMapping.beanProperty(optProp.get());

            if (first) {
                first = false;
            } else {
                builder.append(",");
            }
            builder.append(prop.columnName() + "=?", newValues.get(propName));
        }
        if (first) {
            return true;
        }
        builder.append("WHERE " + pkProperty.columnName() + "=?", pk);

        return executeUnchecked(() ->
                sqlEngine.executeUpdate(conn, builder.build()) == 1
        );
    }
}
