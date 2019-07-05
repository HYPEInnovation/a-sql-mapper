package com.ajjpj.asqlmapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.ajjpj.acollections.util.AUnchecker;

public abstract class AbstractDatabaseTest {

    private static final DataSource innerDs = JdbcConnectionPool.create("jdbc:h2:mem:unittest;MVCC=TRUE;MODE=PostgreSQL", "sa", "");

    protected final DataSource ds = new ResourceBookkeepingDataSource(innerDs);
    protected Connection conn;

    @BeforeEach
    void openConnection() throws SQLException {
        conn = ds.getConnection();
        conn.setAutoCommit(false);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        conn.rollback();
        conn.close();
        assertResourcesReleased();
    }

    protected void executeUpdate(String sql) throws SQLException {
        try (PreparedStatement stmnt = conn.prepareStatement(sql)) {
            stmnt.executeUpdate();
        }
    }

    private void assertResourcesReleased() {
        ((ResourceBookkeepingDataSource) ds).assertResourcesReleased();
    }

    private static class ResourceBookkeepingDataSource implements DataSource {
        private final DataSource delegate;

        private List<ResourceBookkeepingConnection> openedConnections = Collections.synchronizedList(new ArrayList<>());

        private ResourceBookkeepingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        public void assertResourcesReleased() {
            List<Connection> danglingConnections = openedConnections.stream()
                            .filter(c -> !AUnchecker.executeUnchecked(c::isClosed)).collect(Collectors.toList());
            assertEquals(0, danglingConnections.size(), "Unclosed connections found");
            openedConnections.forEach(ResourceBookkeepingConnection::assertResourcesReleased);
        }

        private Connection registerConnection(Connection connection) {
            ResourceBookkeepingConnection con = new ResourceBookkeepingConnection(connection);
            openedConnections.add(con);
            return con;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            return registerConnection(connection);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection connection = delegate.getConnection(username, password);
            return registerConnection(connection);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isAssignableFrom(ResourceBookkeepingDataSource.class))
                return iface.cast(this);
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            if (iface.isAssignableFrom(ResourceBookkeepingDataSource.class))
                return true;
            return delegate.isWrapperFor(iface);
        }
    }

    private static class ResourceBookkeepingConnection implements Connection {

        private final Connection delegate;

        private final List<Statement> createdStatements = new ArrayList<>();

        private final Set<ResultSet> openResultSets = new HashSet<>();

        private ResourceBookkeepingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        private <T extends Statement> T registerStatement(T stmnt) {
            createdStatements.add(stmnt);
            return createStatementWrapper(stmnt);
        }

        private <T extends Statement> T createStatementWrapper(T stmnt) {
            final Class<?> proxyClass;
            if (stmnt instanceof CallableStatement) {
                proxyClass = CallableStatement.class;
            } else if (stmnt instanceof PreparedStatement) {
                proxyClass = PreparedStatement.class;
            } else {
                proxyClass = Statement.class;
            }

            @SuppressWarnings("unchecked")
            T wrapper = (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{ proxyClass}, (proxy, method, args) -> {
                try {
                    Object invoke = method.invoke(stmnt, args);
                    if (invoke instanceof ResultSet) {
                        return createResultSetWrapper((ResultSet) invoke);
                    } else {
                        return invoke;
                    }
                }
                catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
            return wrapper;
        }

        private ResultSet createResultSetWrapper(ResultSet delegate) {
            openResultSets.add(delegate);
            return (ResultSet) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{ ResultSet.class}, (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    openResultSets.remove(delegate);
                }
                return method.invoke(delegate, args);
            });
        }

        void assertResourcesReleased() {
            List<Statement> openStatments = createdStatements.stream().filter(s -> !AUnchecker.executeUnchecked(s::isClosed))
                    .collect(Collectors.toList());
            assertEquals(0, openStatments.size(), "Unclosed statements found");
            //the underlying RS might have been properly closed
            List<ResultSet> actuallyOpenResultSets = openResultSets.stream()
                    .filter(rs -> !AUnchecker.executeUnchecked(rs::isClosed)).collect(Collectors.toList());
            assertEquals(0, actuallyOpenResultSets.size(), "Unclosed resultsets found");
        }

        @Override
        public Statement createStatement() throws SQLException {
            return registerStatement(delegate.createStatement());
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return registerStatement(delegate.prepareStatement(sql));
        }

        @Override
        public CallableStatement prepareCall(String sql) throws SQLException {
            return registerStatement(delegate.prepareCall(sql));
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return delegate.nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return delegate.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            delegate.rollback();
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }

        @Override
        public boolean isClosed() throws SQLException {
            return delegate.isClosed();
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return delegate.getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return delegate.isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            delegate.setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return delegate.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            delegate.setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return delegate.getTransactionIsolation();
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return delegate.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            delegate.clearWarnings();
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return registerStatement(delegate.createStatement(resultSetType, resultSetConcurrency));
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return registerStatement(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency));
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return registerStatement(delegate.prepareCall(sql, resultSetType, resultSetConcurrency));
        }

        @Override
        public Map<String, Class<?>> getTypeMap() throws SQLException {
            return delegate.getTypeMap();
        }

        @Override
        public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
            delegate.setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            delegate.setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return delegate.getHoldability();
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return delegate.setSavepoint();
        }

        @Override
        public Savepoint setSavepoint(String name) throws SQLException {
            return delegate.setSavepoint(name);
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            delegate.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            delegate.releaseSavepoint(savepoint);
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return registerStatement(delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability));
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return registerStatement(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return registerStatement(delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return registerStatement(delegate.prepareStatement(sql, autoGeneratedKeys));
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return registerStatement(delegate.prepareStatement(sql, columnIndexes));
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return registerStatement(delegate.prepareStatement(sql, columnNames));
        }

        @Override
        public Clob createClob() throws SQLException {
            return delegate.createClob();
        }

        @Override
        public Blob createBlob() throws SQLException {
            return delegate.createBlob();
        }

        @Override
        public NClob createNClob() throws SQLException {
            return delegate.createNClob();
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return delegate.createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return delegate.isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, String value) throws SQLClientInfoException {
            delegate.setClientInfo(name, value);
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            delegate.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return delegate.getClientInfo(name);
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return delegate.getClientInfo();
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return delegate.createArrayOf(typeName, elements);
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return delegate.createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            delegate.setSchema(schema);
        }

        @Override
        public String getSchema() throws SQLException {
            return delegate.getSchema();
        }

        @Override
        public void abort(Executor executor) throws SQLException {
            delegate.abort(executor);
        }

        @Override
        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            delegate.setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return delegate.getNetworkTimeout();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isAssignableFrom(ResourceBookkeepingConnection.class))
                return iface.cast(this);
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            if (iface.isAssignableFrom(ResourceBookkeepingConnection.class))
                return true;
            return delegate.isWrapperFor(iface);
        }
    }
}
