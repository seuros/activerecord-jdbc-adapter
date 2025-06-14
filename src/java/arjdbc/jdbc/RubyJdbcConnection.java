/***** BEGIN LICENSE BLOCK *****
 * Copyright (c) 2012-2013 Karol Bucek <self@kares.org>
 * Copyright (c) 2006-2011 Nick Sieger <nick@nicksieger.com>
 * Copyright (c) 2006-2007 Ola Bini <ola.bini@gmail.com>
 * Copyright (c) 2008-2009 Thomas E Enebo <enebo@acm.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ***** END LICENSE BLOCK *****/
package arjdbc.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Date;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.sql.Savepoint;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import arjdbc.util.StringHelper;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBasicObject;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyException;
import org.jruby.RubyFixnum;
import org.jruby.RubyHash;
import org.jruby.RubyIO;
import org.jruby.RubyInteger;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.RubyTime;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.ext.bigdecimal.RubyBigDecimal;
import org.jruby.ext.date.RubyDate;
import org.jruby.ext.date.RubyDateTime;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.builtin.Variable;
import org.jruby.runtime.callsite.CachingCallSite;
import org.jruby.runtime.callsite.FunctionalCachingCallSite;
import org.jruby.runtime.component.VariableEntry;
import org.jruby.util.ByteList;
import org.jruby.util.SafePropertyAccessor;
import org.jruby.util.TypeConverter;

import arjdbc.util.DateTimeUtils;
import arjdbc.util.ObjectSupport;
import arjdbc.util.StringCache;

import static arjdbc.jdbc.DataSourceConnectionFactory.*;
import static arjdbc.util.StringHelper.*;
import static org.jruby.RubyTime.getLocalTimeZone;
import static org.jruby.api.Access.getModule;
import static org.jruby.api.Access.objectClass;
import static org.jruby.api.Convert.asFixnum;
import static org.jruby.api.Convert.toInt;
import static org.jruby.api.Create.allocArray;
import static org.jruby.api.Create.newArray;
import static org.jruby.api.Create.newArrayNoCopy;
import static org.jruby.api.Create.newEmptyArray;


/**
 * Most of our ActiveRecord::ConnectionAdapters::JdbcConnection implementation.
 */
public class RubyJdbcConnection extends RubyObject {

    private static final long serialVersionUID = 3803945791317576818L;

    private static final String[] TABLE_TYPE = new String[] { "TABLE" };
    private static final String[] TABLE_TYPES = new String[] { "TABLE", "VIEW", "SYNONYM" };

    private ConnectionFactory connectionFactory;
    private IRubyObject config;
    private IRubyObject adapter; // the AbstractAdapter instance we belong to
    private volatile boolean connected = true;
    private RubyClass attributeClass;
    private RubyClass timeZoneClass;

    private boolean lazy = false; // final once set on initialize
    private boolean jndi; // final once set on initialize
    private boolean configureConnection = true; // final once initialized
    private int fetchSize = 0; // 0 = JDBC default

    protected RubyJdbcConnection(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
        var context = runtime.getCurrentContext();
        attributeClass = getModule(context, "ActiveModel").getClass(context, "Attribute");
        timeZoneClass = getModule(context, "ActiveSupport").getClass(context, "TimeWithZone");
    }

    private static final ObjectAllocator ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyJdbcConnection(runtime, klass);
        }
    };

    public static RubyClass createJdbcConnectionClass(final Ruby runtime) {
        var context = runtime.getCurrentContext();
        return getConnectionAdapters(context).
                defineClassUnder(context, "JdbcConnection", runtime.getObject(), ALLOCATOR).
                defineMethods(context, RubyJdbcConnection.class);
    }

    public static RubyClass getJdbcConnection(ThreadContext context) {
        return getConnectionAdapters(context).getClass(context, "JdbcConnection");
    }

    protected static RubyModule ActiveRecord(ThreadContext context) {
        return getModule(context, "ActiveRecord");
    }

    @Deprecated
    public static RubyClass getBase(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass("Base");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::Result</code>
     */
    public static RubyClass getResult(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass(context, "Result");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::ConnectionAdapters</code>
     */
    public static RubyModule getConnectionAdapters(ThreadContext context) {
        return getModule(context, "ActiveRecord").getModule(context, "ConnectionAdapters");
    }

    /**
     * resolve <code>ActiveRecord::ConnectionAdapters::IndexDefinition</code>.
     *
     * @param context the thread context
     * @return <code>ActiveRecord::ConnectionAdapters::IndexDefinition</code>
     */
    protected static RubyClass indexDefinition(ThreadContext context) {
        return getConnectionAdapters(context).getClass(context, "IndexDefinition");
    }

    /**
     * resolve <code>ActiveRecord::ConnectionAdapters::ForeignKeyDefinition</code>.
     *
     * @param context the thread context.
     * @return <code>ActiveRecord::ConnectionAdapters::ForeignKeyDefinition</code>
     * @note only since AR 4.2
     */
    protected static RubyClass foreignKeyDefinition(ThreadContext context) {
        return getConnectionAdapters(context).getClass(context, "ForeignKeyDefinition");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::JDBCError</code>
     */
    protected static RubyClass getJDBCError(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass(context, "JDBCError");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::ConnectionNotEstablished</code>
     */
    protected static RubyClass getConnectionNotEstablished(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass(context, "ConnectionNotEstablished");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::NoDatabaseError</code>
     */
    protected static RubyClass getNoDatabaseError(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass(context, "NoDatabaseError");
    }

    /**
     * @param context the thread context
     * @return <code>ActiveRecord::TransactionIsolationError</code>
     */
    protected static RubyClass getTransactionIsolationError(ThreadContext context) {
        return getModule(context, "ActiveRecord").getClass(context, "TransactionIsolationError");
    }

    @JRubyMethod(name = "transaction_isolation", alias = "get_transaction_isolation")
    public IRubyObject get_transaction_isolation(final ThreadContext context) {
        return withConnection(context, connection -> {
            final int level = connection.getTransactionIsolation();
            final String isolationSymbol = formatTransactionIsolationLevel(level);
            if ( isolationSymbol == null ) return context.nil;
            return context.runtime.newSymbol(isolationSymbol);
        });
    }

    @JRubyMethod(name = "transaction_isolation=", alias = "set_transaction_isolation")
    public IRubyObject set_transaction_isolation(final ThreadContext context, final IRubyObject isolation) {
        return withConnection(context, connection -> {
            final int level;
            if ( isolation.isNil() ) {
                level = connection.getMetaData().getDefaultTransactionIsolation();
            }
            else {
                level = mapTransactionIsolationLevel(isolation);
            }

            connection.setTransactionIsolation(level);

            final String isolationSymbol = formatTransactionIsolationLevel(level);
            if ( isolationSymbol == null ) return context.nil;
            return context.runtime.newSymbol(isolationSymbol);
        });
    }

    public static String formatTransactionIsolationLevel(final int level) {
        if ( level == Connection.TRANSACTION_READ_UNCOMMITTED ) return "read_uncommitted"; // 1
        if ( level == Connection.TRANSACTION_READ_COMMITTED ) return "read_committed"; // 2
        if ( level == Connection.TRANSACTION_REPEATABLE_READ ) return "repeatable_read"; // 4
        if ( level == Connection.TRANSACTION_SERIALIZABLE ) return "serializable"; // 8
        if ( level == 0 ) return null;
        throw new IllegalArgumentException("unexpected transaction isolation level: " + level);
    }

    /*
      def transaction_isolation_levels
        {
          read_uncommitted: "READ UNCOMMITTED",
          read_committed:   "READ COMMITTED",
          repeatable_read:  "REPEATABLE READ",
          serializable:     "SERIALIZABLE"
        }
      end
    */

    public static int mapTransactionIsolationLevel(final IRubyObject isolation) {
        final Object isolationString;
        if ( isolation instanceof RubySymbol ) {
            isolationString = ((RubySymbol) isolation).asJavaString(); // RubySymbol (interned)
        }
        else {
            isolationString = isolation.asString().toString().toLowerCase(Locale.ENGLISH).intern();
        }

        if ( isolationString == "read_uncommitted" ) return Connection.TRANSACTION_READ_UNCOMMITTED; // 1
        if ( isolationString == "read_committed" ) return Connection.TRANSACTION_READ_COMMITTED; // 2
        if ( isolationString == "repeatable_read" ) return Connection.TRANSACTION_REPEATABLE_READ; // 4
        if ( isolationString == "serializable" ) return Connection.TRANSACTION_SERIALIZABLE; // 8

        throw new IllegalArgumentException(
                "unexpected isolation level: " + isolation + " (" + isolationString + ")"
        );
    }

    @JRubyMethod(name = "supports_transaction_isolation?", optional = 1)
    public IRubyObject supports_transaction_isolation_p(final ThreadContext context,
        final IRubyObject[] args) throws SQLException {
        final IRubyObject isolation = args.length > 0 ? args[0] : null;

        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final DatabaseMetaData metaData = connection.getMetaData();
            final boolean supported;
            if ( isolation != null && ! isolation.isNil() ) {
                final int level = mapTransactionIsolationLevel(isolation);
                supported = metaData.supportsTransactionIsolationLevel(level);
            }
            else {
                final int level = metaData.getDefaultTransactionIsolation();
                supported = level > Connection.TRANSACTION_NONE; // > 0
            }
            return context.runtime.newBoolean(supported);
        });
    }

    @JRubyMethod(name = {"begin", "transaction"}, required = 1) // optional isolation argument for AR-4.0
    public IRubyObject begin(final ThreadContext context, final IRubyObject isolation) {
        try { // handleException == false so we can handle setTXIsolation
            return withConnection(context, false, connection -> beginTransaction(context, connection, isolation == context.nil ? null : isolation));
        } catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = {"begin", "transaction"}) // optional isolation argument for AR-4.0
    public IRubyObject begin(final ThreadContext context) {
        try { // handleException == false so we can handle setTXIsolation
            return withConnection(context, false, connection -> beginTransaction(context, connection, null));
        } catch (SQLException e) {
            return handleException(context, e);
        }
    }

    protected IRubyObject beginTransaction(final ThreadContext context, final Connection connection,
        final IRubyObject isolation) throws SQLException {
        if ( isolation != null ) {
            setTransactionIsolation(context, connection, isolation);
        }
        if ( connection.getAutoCommit() ) connection.setAutoCommit(false);
        return context.nil;
    }

    protected void setTransactionIsolation(final ThreadContext context, final Connection connection,
        final IRubyObject isolation) throws SQLException {
        final int level = mapTransactionIsolationLevel(isolation);
        try {
            connection.setTransactionIsolation(level);
        }
        catch (SQLException e) {
            RubyClass txError = ActiveRecord(context).getClass(context, "TransactionIsolationError");
            if ( txError != null ) throw wrapException(context, txError, e);
            throw e; // let it roll - will be wrapped into a JDBCError (non 4.0)
        }
    }

    @JRubyMethod(name = "commit")
    public IRubyObject commit(final ThreadContext context) {
        try {
            final Connection connection = getConnectionInternal(true);
            if ( ! connection.getAutoCommit() ) {
                try {
                    connection.commit();
                    resetSavepoints(context, connection); // if any
                    return context.runtime.newBoolean(true);
                }
                finally {
                    connection.setAutoCommit(true);
                }
            }
            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "rollback")
    public IRubyObject rollback(final ThreadContext context) {
        try {
            final Connection connection = getConnectionInternal(true);
            if ( ! connection.getAutoCommit() ) {
                try {
                    connection.rollback();
                    resetSavepoints(context, connection); // if any
                    return context.tru;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "supports_savepoints?")
    public IRubyObject supports_savepoints_p(final ThreadContext context) throws SQLException {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final DatabaseMetaData metaData = connection.getMetaData();
            return context.runtime.newBoolean( metaData.supportsSavepoints() );
        });
    }

    @JRubyMethod(name = "create_savepoint")  // not used
    public IRubyObject create_savepoint(final ThreadContext context) {
        return create_savepoint(context, context.nil);
    }

    @JRubyMethod(name = "create_savepoint", required = 1)
    public IRubyObject create_savepoint(final ThreadContext context, IRubyObject name) {
        try {
            final Connection connection = getConnectionInternal(true);
            connection.setAutoCommit(false);

            final Savepoint savepoint ;
            // NOTE: this will auto-start a DB transaction even invoked outside
            // of a AR (Ruby) transaction (`transaction { ... create_savepoint }`)
            // it would be nice if AR knew about this TX although that's kind of
            // "really advanced" functionality - likely not to be implemented ...
            if ( name != context.nil ) {
                savepoint = connection.setSavepoint(name.toString());
            }
            else {
                savepoint = connection.setSavepoint();
                name = RubyString.newString( context.runtime, Integer.toString( savepoint.getSavepointId() ));
            }
            getSavepoints(context).put(name, savepoint);

            return name;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "rollback_savepoint", required = 1)
    public IRubyObject rollback_savepoint(final ThreadContext context, final IRubyObject name) {
        if (name == context.nil) throw context.runtime.newArgumentError("nil savepoint name given");

        try {
            final Connection connection = getConnectionInternal(true);
            Savepoint savepoint = getSavepoints(context).get(name);
            if ( savepoint == null ) {
                throw context.runtime.newRuntimeError("could not rollback savepoint: '" + name + "' (not set)");
            }
            connection.rollback(savepoint);
            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "release_savepoint", required = 1)
    public IRubyObject release_savepoint(final ThreadContext context, final IRubyObject name) {
        if (name == context.nil) throw context.runtime.newArgumentError("nil savepoint name given");

        try {
            Object savepoint = getSavepoints(context).remove(name);

            if (savepoint == null) throw newSavepointNotSetError(context, name, "release");

            // NOTE: RubyHash.remove does not convert to Java as get does :
            if (!(savepoint instanceof Savepoint)) {
                savepoint = ((IRubyObject) savepoint).toJava(Savepoint.class);
            }

            final Connection connection = getConnectionInternal(true);
            releaseSavepoint(connection, (Savepoint) savepoint);
            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
    }

    // MSSQL doesn't support releasing savepoints so we make it possible to override the actual release action
    protected void releaseSavepoint(final Connection connection, final Savepoint savepoint) throws SQLException {
        connection.releaseSavepoint(savepoint);
    }

    protected static RuntimeException newSavepointNotSetError(final ThreadContext context, final IRubyObject name, final String op) {
        RubyClass StatementInvalid = ActiveRecord(context).getClass(context, "StatementInvalid");
        return context.runtime.newRaiseException(StatementInvalid, "could not " + op + " savepoint: '" + name + "' (not set)");
    }

    // NOTE: this is iternal API - not to be used by user-code !
    @JRubyMethod(name = "marked_savepoint_names")
    public IRubyObject marked_savepoint_names(final ThreadContext context) {
        @SuppressWarnings("unchecked")
        final Map<IRubyObject, Savepoint> savepoints = getSavepoints(false);
        if ( savepoints != null ) {
            final RubyArray names = allocArray(context, savepoints.size());
            for ( Map.Entry<IRubyObject, ?> entry : savepoints.entrySet() ) {
                names.append(context, entry.getKey()); // keys are RubyString instances
            }
            return names;
        }
        return newEmptyArray(context);
    }

    protected Map<IRubyObject, Savepoint> getSavepoints(final ThreadContext context) {
        return getSavepoints(true);
    }

    @SuppressWarnings("unchecked")
    private Map<IRubyObject, Savepoint> getSavepoints(final boolean init) {
        if ( hasInternalVariable("savepoints") ) {
            return (Map<IRubyObject, Savepoint>) getInternalVariable("savepoints");
        }
        if ( init ) {
            Map<IRubyObject, Savepoint> savepoints = new LinkedHashMap<>(4);
            setInternalVariable("savepoints", savepoints);
            return savepoints;
        }
        return null;
    }

    protected boolean resetSavepoints(final ThreadContext context, final Connection connection) throws SQLException {
        if ( hasInternalVariable("savepoints") ) {
            removeInternalVariable("savepoints");
            return true;
        }
        return false;
    }

    @JRubyMethod(required = 2)
    public final IRubyObject initialize(final ThreadContext context, final IRubyObject config, final IRubyObject adapter) {
        doInitialize(context, config, adapter);
        return this;
    }

    protected void doInitialize(final ThreadContext context, final IRubyObject config, final IRubyObject adapter) {
        this.config = config;
        this.adapter = adapter;

        this.jndi = setupConnectionFactory(context);
        this.lazy = jndi; // JNDIs are lazy by default otherwise eager
        try {
            if (adapter == null || adapter == context.nil) {
                warn(context, "adapter not set, please pass adapter on JdbcConnection#initialize(config, adapter)");
            }

            if (!lazy) setConnection(newConnection());
        }
        catch (SQLException e) {
            String message = e.getMessage();
            if ( message == null ) message = e.getSQLState();
            throw wrapException(context, e, message);
        }

        IRubyObject value = getConfigValue(context, "configure_connection");
        if ( value == context.nil ) this.configureConnection = true;
        else {
            this.configureConnection = value != context.fals;
        }

        IRubyObject jdbcFetchSize = getConfigValue(context, "jdbc_fetch_size");
        if (jdbcFetchSize != context.nil) {
            this.fetchSize = toInt(context, jdbcFetchSize);
        }
    }

    @JRubyMethod(name = "adapter")
    public IRubyObject adapter(final ThreadContext context) {
        return adapter == null ? context.nil : adapter;
    }

    @JRubyMethod(name = "connection_factory")
    public IRubyObject connection_factory() {
        return convertJavaToRuby( getConnectionFactory() );
    }

    @JRubyMethod(name = "connection_factory=", required = 1)
    public IRubyObject set_connection_factory(final IRubyObject factory) {
        setConnectionFactory( (ConnectionFactory) factory.toJava(ConnectionFactory.class) );
        return factory;
    }

    private void configureConnection() {
        if ( ! configureConnection ) return; // return false;

        if ( adapter != null && ! adapter.isNil() ) {
            if ( adapter.respondsTo("configure_connection") ) {
                final ThreadContext context = getRuntime().getCurrentContext();
                adapter.callMethod(context, "configure_connection");
            }
        }
    }

    @JRubyMethod(name = "configure_connection")
    public IRubyObject configure_connection(final ThreadContext context) {
        if ( ! lazy || getConnectionImpl() != null ) configureConnection();
        return context.nil;
    }

    @JRubyMethod(name = "jdbc_connection", alias = "connection")
    public final IRubyObject connection(final ThreadContext context) {
        return convertJavaToRuby( connectionImpl(context) );
    }

    @JRubyMethod(name = "jdbc_connection", alias = "connection", required = 1)
    public final IRubyObject connection(final ThreadContext context, final IRubyObject unwrap) {
        if ( unwrap == context.nil || unwrap == context.fals ) {
            return connection(context);
        }
        Connection connection = connectionImpl(context);
        try {
            if ( connection.isWrapperFor(Connection.class) ) {
                return convertJavaToRuby( connection.unwrap(Connection.class) );
            }
        }
        catch (AbstractMethodError | SQLException e) {
            debugStackTrace(context, e);
            warn(context, "driver/pool connection does not support unwrapping: " + e);
        }
        return convertJavaToRuby( connection );
    }

    private Connection connectionImpl(final ThreadContext context) {
        Connection connection = getConnection(false);
        if ( connection == null ) {
            synchronized (this) {
                connection = getConnection(false);
                if ( connection == null ) {
                    reconnect(context);
                    connection = getConnection(false);
                }
            }
        }
        return connection;
    }

    @JRubyMethod(name = "active?", alias = "valid?")
    public RubyBoolean active_p(final ThreadContext context) {
        if ( ! connected ) return context.fals;
        if (jndi) {
            // for JNDI the data-source / pool is supposed to
            // manage connections for us thus no valid check!
            boolean active = getConnectionFactory() != null;
            return context.runtime.newBoolean( active );
        }
        final Connection connection = getConnection(false);
        if ( connection == null ) return context.fals; // unlikely
        return context.runtime.newBoolean( isConnectionValid(context, connection) );
    }

    @JRubyMethod(name = "really_valid?")
    public RubyBoolean really_valid_p(final ThreadContext context) {
        final Connection connection = getConnection(true);
        if (connection == null) return context.fals;
        return context.runtime.newBoolean(isConnectionValid(context, connection));
    }

    @JRubyMethod(name = "disconnect!")
    public synchronized IRubyObject disconnect(final ThreadContext context) {
        setConnection(null); connected = false;
        return context.nil;
    }

    @JRubyMethod(name = "reconnect!")
    public synchronized IRubyObject reconnect(final ThreadContext context) {
        try {
            connectImpl( ! lazy ); connected = true;
        }
        catch (SQLException e) {
            debugStackTrace(context, e);
            handleException(context, e);
        }
        return context.nil;
    }

    private void connectImpl(final boolean forceConnection) throws SQLException {
        setConnection( forceConnection ? newConnection() : null );
        if (forceConnection) {
            if (getConnectionImpl() == null) throw new SQLException("Didn't get a connection. Wrong URL?");
            configureConnection();
        }
    }

    @JRubyMethod(name = "read_only?")
    public IRubyObject is_read_only(final ThreadContext context) {
        try {
        final Connection connection = getConnectionInternal(false);
            if (connection != null) {
                return context.runtime.newBoolean(connection.isReadOnly());
            }
        } catch (SQLException e) {
            return handleException(context, e);
        }
        return context.nil;
    }

    @JRubyMethod(name = "read_only=")
    public IRubyObject set_read_only(final ThreadContext context, final IRubyObject flag) {
        try {
            final Connection connection = getConnectionInternal(true);
            connection.setReadOnly( flag.isTrue() );
            return context.runtime.newBoolean( connection.isReadOnly() );
        } catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = { "open?" /* "conn?" */ })
    public IRubyObject open_p(final ThreadContext context) {
        try {
            final Connection connection = getConnectionInternal(false);

            if (connection == null) return context.fals;

            // NOTE: isClosed method generally cannot be called to determine
            // whether a connection to a database is valid or invalid ...
            return context.runtime.newBoolean(!connection.isClosed());
        } catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p(ThreadContext context) {
        try {
            final Connection connection = getConnectionInternal(false);

            if (connection == null) return context.fals;

            // NOTE: isClosed method generally cannot be called to determine
            // whether a connection to a database is valid or invalid ...
            return context.runtime.newBoolean(connection.isClosed());
        } catch (SQLException e) {
            return handleException(context, e);
        }
    }

    @JRubyMethod(name = "close")
    public IRubyObject close(final ThreadContext context) {
        final Connection connection = getConnection(false);

        if (connection == null) return context.fals;

        try {
            if (connection.isClosed()) return context.fals;

            setConnection(null); // does connection.close();
        } catch (Exception e) {
            debugStackTrace(context, e);
            return context.nil;
        }

        // ActiveRecord expects a closed connection to not try and re-open a connection
        // whereas JNDI expects that.
        if (!jndi) disconnect(context);

        return context.tru;
    }

    @JRubyMethod(name = "database_name")
    public IRubyObject database_name(final ThreadContext context) {
        return withConnection(context, connection -> {
            String name = connection.getCatalog();
            if ( name == null ) {
                name = connection.getMetaData().getUserName();
                if ( name == null ) return context.nil;
            }
            return context.runtime.newString(name);
        });
    }

    @JRubyMethod(name = "execute", required = 1)
    public IRubyObject execute(final ThreadContext context, final IRubyObject sql) {
        final String query = sqlString(sql);
        return withConnection(context, connection -> {
            Statement statement = null;
            try {
                statement = createStatement(context, connection);

                // For DBs that do support multiple statements, lets return the last result set
                // to be consistent with AR
                boolean hasResultSet = doExecute(statement, query);
                int updateCount = statement.getUpdateCount();

                IRubyObject result = context.nil; // If no results, return nil
                ResultSet resultSet;

                while (hasResultSet || updateCount != -1) {

                    if (hasResultSet) {
                        resultSet = statement.getResultSet();

                        // Unfortunately the result set gets closed when getMoreResults()
                        // is called, so we have to process the result sets as we get them
                        // this shouldn't be an issue in most cases since we're only getting 1 result set anyways
                        //result = mapExecuteResult(context, connection, resultSet);
                        result = mapToRawResult(context, connection, resultSet, false);
                        resultSet.close();
                    } else {
                        result = context.runtime.newFixnum(updateCount);
                    }

                    // Check to see if there is another result set
                    hasResultSet = statement.getMoreResults();
                    updateCount = statement.getUpdateCount();
                }

                return result;

            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    protected Statement createStatement(final ThreadContext context, final Connection connection)
        throws SQLException {
        final Statement statement = connection.createStatement();
        IRubyObject escapeProcessing = getConfigValue(context, "statement_escape_processing");
        // NOTE: disable (driver) escape processing by default, it's not really
        // needed for AR statements ... if users need it they might configure :
        if ( escapeProcessing == context.nil ) {
            statement.setEscapeProcessing(false);
        }
        else {
            statement.setEscapeProcessing(escapeProcessing.isTrue());
        }
        if (fetchSize != 0) statement.setFetchSize(fetchSize);
        return statement;
    }

    /**
     * Execute a query using the given statement.
     * @param statement
     * @param query
     * @return true if the first result is a <code>ResultSet</code>;
     *         false if it is an update count or there are no results
     * @throws SQLException
     */
    protected boolean doExecute(final Statement statement, final String query) throws SQLException {
        return statement.execute(query);
    }

    protected IRubyObject mapExecuteResult(final ThreadContext context,
            final Connection connection, final ResultSet resultSet) throws SQLException{

        return mapQueryResult(context, connection, resultSet);
    }

    private static String[] createStatementPk(IRubyObject pk) {
        String[] statementPk;
        if (pk instanceof RubyArray) {
            RubyArray ary = (RubyArray) pk;
            int size = ary.size();
            statementPk = new String[size];
            for (int i = 0; i < size; i++) {
                statementPk[i] = sqlString(ary.eltInternal(i));
            }
        } else {
            statementPk = new String[] { sqlString(pk) };
        }
        return statementPk;
    }

    /**
     * Executes an INSERT SQL statement
     * @param context
     * @param sql
     * @param pk Rails PK
     * @return ActiveRecord::Result
     * @throws SQLException
     */
    @JRubyMethod(name = "execute_insert_pk", required = 2)
    public IRubyObject execute_insert_pk(final ThreadContext context, final IRubyObject sql, final IRubyObject pk) {
        return withConnection(context, connection -> {
            Statement statement = null;
            final String query = sqlString(sql);
            try {

                statement = createStatement(context, connection);

                if (pk == context.nil || pk == context.fals || !supportsGeneratedKeys(connection)) {
                    statement.executeUpdate(query, Statement.RETURN_GENERATED_KEYS);
                } else {
                    statement.executeUpdate(query, createStatementPk(pk));
                }

                return mapGeneratedKeys(context, connection, statement);
            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    @Deprecated
    @JRubyMethod(name = "execute_insert", required = 1)
    public IRubyObject execute_insert(final ThreadContext context, final IRubyObject sql) {
        return execute_insert_pk(context, sql, context.nil);
    }

    /**
     * Executes an INSERT SQL statement using a prepared statement
     * @param context
     * @param sql
     * @param binds RubyArray of values to be bound to the query
     * @param pk Rails PK
     * @return ActiveRecord::Result
     * @throws SQLException
     */
    @JRubyMethod(name = "execute_insert_pk", required = 3)
    public IRubyObject execute_insert_pk(final ThreadContext context, final IRubyObject sql, final IRubyObject binds,
                                         final IRubyObject pk) {
        return withConnection(context, connection -> {
            PreparedStatement statement = null;
            final String query = sqlString(sql);
            try {
                if (pk == context.nil || pk == context.fals || !supportsGeneratedKeys(connection)) {
                    statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
                } else {
                    statement = connection.prepareStatement(query, createStatementPk(pk));
                }

                setStatementParameters(context, connection, statement, (RubyArray) binds);
                statement.executeUpdate();
                return mapGeneratedKeys(context, connection, statement);
            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    @Deprecated
    @JRubyMethod(name = "execute_insert", required = 2)
    public IRubyObject execute_insert(final ThreadContext context, final IRubyObject binds, final IRubyObject sql) {
        return execute_insert_pk(context, sql, binds, context.nil);
    }

    /**
     * Executes an UPDATE (DELETE) SQL statement
     * @param context
     * @param sql
     * @return affected row count
     * @throws SQLException
     */
    @JRubyMethod(name = {"execute_update", "execute_delete"}, required = 1)
    public IRubyObject execute_update(final ThreadContext context, final IRubyObject sql) {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            Statement statement = null;
            final String query = sqlString(sql);

            try {
                statement = createStatement(context, connection);

                final int rowCount = statement.executeUpdate(query);
                return context.runtime.newFixnum(rowCount);
            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    /**
     * Executes an UPDATE (DELETE) SQL using a prepared statement
     * @param context
     * @param sql
     * @return affected row count
     * @throws SQLException
     *
     * @see #execute_update(ThreadContext, IRubyObject)
     */
    @JRubyMethod(name = {"execute_prepared_update", "execute_prepared_delete"}, required = 2)
    public IRubyObject execute_prepared_update(final ThreadContext context, final IRubyObject sql, final IRubyObject binds) {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            PreparedStatement statement = null;
            final String query = sqlString(sql);
            try {
                statement = connection.prepareStatement(query);
                setStatementParameters(context, connection, statement, (RubyArray) binds);
                final int rowCount = statement.executeUpdate();
                return context.runtime.newFixnum(rowCount);
            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    /**
     * This is the same as execute_query but it will return a list of hashes.
     *
     * @see RubyJdbcConnection#execute_query(ThreadContext, IRubyObject)
     * @param context which context this method is executing on.
     * @param args arguments being supplied to this method.
     * @param block (optional) block to yield row values (Hash(name: value))
     * @return List of Hash(name: value) unless block is given.
     * @throws SQLException when a database error occurs<
     */
    @JRubyMethod(required = 1, optional = 2)
    public IRubyObject execute_query_raw(final ThreadContext context, final IRubyObject[] args, final Block block) {
        final String query = sqlString( args[0] ); // sql
        final RubyArray binds;
        final int maxRows;

        // args: (sql), (sql, max_rows), (sql, binds), (sql, max_rows, binds)
        switch (args.length) {
            case 2:
                if (args[1] instanceof RubyNumeric) { // (sql, max_rows)
                    maxRows = toInt(context, args[1]);
                    binds = null;
                } else {                              // (sql, binds)
                    maxRows = 0;
                    binds = (RubyArray) TypeConverter.checkArrayType(context, args[1]);
                }
                break;
            case 3:                                   // (sql, max_rows, binds)
                maxRows = toInt(context, args[1]);
                binds = (RubyArray) TypeConverter.checkArrayType(context, args[2]);
                break;
            default:                                  // (sql) 1-arg
                maxRows = 0;
                binds = null;
                break;
        }

        return doExecuteQueryRaw(context, query, maxRows, block, binds);
    }

    private IRubyObject doExecuteQueryRaw(final ThreadContext context,
        final String query, final int maxRows, final Block block, final RubyArray binds) {
        return withConnection(context, connection -> {
            Statement statement = null; boolean hasResult;
            try {
                if ( binds == null || binds.isEmpty()) { // plain statement
                    statement = createStatement(context, connection);
                    statement.setMaxRows(maxRows); // zero means there is no limit
                    hasResult = statement.execute(query);
                }
                else {
                    final PreparedStatement prepStatement;
                    statement = prepStatement = connection.prepareStatement(query);
                    if (fetchSize != 0) statement.setFetchSize(fetchSize);
                    statement.setMaxRows(maxRows); // zero means there is no limit
                    setStatementParameters(context, connection, prepStatement, binds);
                    hasResult = prepStatement.execute();
                }

                if (block.isGiven()) {
                    if (hasResult) {
                        // yield(id1, name1) ... row 1 result data
                        // yield(id2, name2) ... row 2 result data
                        return yieldResultRows(context, connection, statement.getResultSet(), block);
                    }
                    return context.nil;
                }
                if (hasResult) {
                    return mapToRawResult(context, connection, statement.getResultSet(), false);
                }
                return newEmptyArray(context);
            }
            catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            }
            finally {
                close(statement);
            }
        });
    }

    protected static String sqlString(final IRubyObject sql) {
        return sql.convertToString().decodeString();
    }

    /**
     * Executes a query and returns the (AR) result
     *
     * @param context which context this method is executing on
     * @param sql the query to execute
     * @return a Ruby <code>ActiveRecord::Result</code> instance
     * @throws SQLException when a database error occurs
     */
    @JRubyMethod(required = 1)
    public IRubyObject execute_query(final ThreadContext context, final IRubyObject sql) {
        return withConnection(context, connection -> {
            Statement statement = null;
            final String query = sqlString(sql);
            try {
                statement = createStatement(context, connection);

                // At least until AR 5.1 #exec_query still gets called for things that don't return results in some cases :(
                if (statement.execute(query)) {
                    return mapQueryResult(context, connection, statement.getResultSet());
                }

                return newEmptyResult(context);

            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    @JRubyMethod(required = 1)
    public IRubyObject get_first_value(final ThreadContext context, final IRubyObject sql) {
        return withConnection(context, connection -> {
            Statement statement = null;
            final String query = sqlString(sql);
            try {
                statement = createStatement(context, connection);
                statement.execute(query);
                ResultSet rs = statement.getResultSet();
                if (rs == null || !rs.next()) return context.nil;

                return jdbcToRuby(context, context.getRuntime(), 1, rs.getMetaData().getColumnType(1), rs);

            } catch (final SQLException e) {
                debugErrorSQL(context, query);
                throw e;
            } finally {
                close(statement);
            }
        });
    }

    /**
     * Prepares a query, returns a wrapped PreparedStatement. This takes care of exception wrapping
     * @param context which context this method is executing on.
     * @param sql the query to prepare-
     * @return a Ruby <code>PreparedStatement</code>
     */
    @JRubyMethod(required = 1)
    public IRubyObject prepare_statement(final ThreadContext context, final IRubyObject sql) {
        return withConnection(context, connection -> {
            final String query = sql.convertToString().getUnicodeValue();
            PreparedStatement statement = connection.prepareStatement(query);
            if (fetchSize != 0) statement.setFetchSize(fetchSize);
            return JavaUtil.convertJavaToRuby(context.runtime, statement);
        });
    }

    // Called from exec_query in abstract/database_statements
    /**
     * Executes a query and returns the (AR) result.  There are three parameters:
     * <ul>
     *     <li>sql - String of sql</li>
     *     <li>binds - Array of bindings for a prepared statement</li>
     *     <li>cached_statement - A prepared statement object that should be used instead of creating a new statement</li>
     * </ul>
     *
     * @param context which context this method is executing on.
     * @param sql the query to execute.
     * @param binds an array of values to be set as parameters
     * @param cachedStatement a wrapped <code>PreparedStatement</code> to use instead of creating a new <code>Statement</code>
     * @return a Ruby <code>ActiveRecord::Result</code> instance
     * @throws SQLException when a database error occurs
     */
    @JRubyMethod(required = 3)
    public IRubyObject execute_prepared_query(final ThreadContext context, final IRubyObject sql,
        final IRubyObject binds, final IRubyObject cachedStatement) {
        return withConnection(context, connection -> {
            final boolean cached = !(cachedStatement == null || cachedStatement.isNil());
            String query = null;
            PreparedStatement statement = null;

            try {
                if (cached) {
                    statement = (PreparedStatement) JavaEmbedUtils.rubyToJava(cachedStatement);
                } else {
                    query = sql.convertToString().getUnicodeValue();
                    statement = connection.prepareStatement(query);
                    if (fetchSize != 0) statement.setFetchSize(fetchSize);
                }

                setStatementParameters(context, connection, statement, (RubyArray) binds);

                if (statement.execute()) {
                    ResultSet resultSet = statement.getResultSet();
                    IRubyObject results = mapQueryResult(context, connection, resultSet);
                    resultSet.close();

                    return results;
                } else {
                    return newEmptyResult(context);
                }
            } catch (final SQLException e) {
                if (query == null) query = sql.convertToString().getUnicodeValue();
                debugErrorSQL(context, query);
                throw e;
            } finally {
                if ( cached ) {
                    statement.clearParameters();
                } else {
                    close(statement);
                }
            }
        });
    }

    protected IRubyObject mapQueryResult(final ThreadContext context,
        final Connection connection, final ResultSet resultSet) throws SQLException {
        final ColumnData[] columns = extractColumns(context, connection, resultSet, false);
        return mapToResult(context, connection, resultSet, columns);
    }

    @JRubyMethod(name = "supported_data_types")
    public IRubyObject supported_data_types(final ThreadContext context) throws SQLException {
        final Connection connection = getConnection(true);
        final ResultSet typeDesc = connection.getMetaData().getTypeInfo();
        final IRubyObject types;
        try {
            types = mapToRawResult(context, connection, typeDesc, true);
        }
        finally { close(typeDesc); }

        return types;
    }

    @JRubyMethod(name = "primary_keys", required = 1)
    public IRubyObject primary_keys(ThreadContext context, IRubyObject tableName) throws SQLException {
        @SuppressWarnings("unchecked")
        List<IRubyObject> primaryKeys = (List) primaryKeys(context, tableName.toString());
        return newArray(context, primaryKeys);
    }

    protected static final int PRIMARY_KEYS_COLUMN_NAME = 4;

    private List<RubyString> primaryKeys(final ThreadContext context, final String tableName) {
        return withConnection(context, connection -> {
            final String _tableName = caseConvertIdentifierForJdbc(connection, tableName);
            final TableName table = extractTableName(connection, null, null, _tableName);
            return primaryKeys(context, connection, table);
        });
    }

    protected List<RubyString> primaryKeys(final ThreadContext context,
        final Connection connection, final TableName table) throws SQLException {
        final DatabaseMetaData metaData = connection.getMetaData();
        ResultSet resultSet = null;
        final List<RubyString> keyNames = new ArrayList<>();
        try {
            resultSet = metaData.getPrimaryKeys(table.catalog, table.schema, table.name);
            final Ruby runtime = context.runtime;
            while ( resultSet.next() ) {
                String columnName = resultSet.getString(PRIMARY_KEYS_COLUMN_NAME);
                columnName = caseConvertIdentifierForRails(connection, columnName);
                keyNames.add( RubyString.newUnicodeString(runtime, columnName) );
            }
        }
        finally { close(resultSet); }
        return keyNames;
    }

    @JRubyMethod(name = "tables", required = 0, optional = 4)
    public IRubyObject tables(final ThreadContext context, final IRubyObject[] args) {
        switch ( args.length ) {
            case 0: // ()
                return tables(context, null, null, null, TABLE_TYPE);
            case 1: // (catalog)
                return tables(context, toStringOrNull(args[0]), null, null, TABLE_TYPE);
            case 2: // (catalog, schemaPattern)
                return tables(context, toStringOrNull(args[0]), toStringOrNull(args[1]), null, TABLE_TYPE);
            case 3: // (catalog, schemaPattern, tablePattern)
                return tables(context, toStringOrNull(args[0]), toStringOrNull(args[1]), toStringOrNull(args[2]), TABLE_TYPE);
        }
        return tables(context, toStringOrNull(args[0]), toStringOrNull(args[1]), toStringOrNull(args[2]), getTypes(args[3]));
    }

    protected IRubyObject tables(final ThreadContext context,
        final String catalog, final String schemaPattern, final String tablePattern, final String[] types) {
        return withConnection(context, connection -> matchTables(context, connection, catalog, schemaPattern, tablePattern, types, false));
    }

    protected String[] getTableTypes() {
        return TABLE_TYPES;
    }

    @JRubyMethod(name = "table_exists?")
    public IRubyObject table_exists_p(final ThreadContext context, IRubyObject table) {
        if ( table.isNil() ) {
            throw context.runtime.newArgumentError("nil table name");
        }
        final String tableName = table.toString();

        return tableExists(context, null, tableName);
    }

    @JRubyMethod(name = "table_exists?")
    public IRubyObject table_exists_p(final ThreadContext context, IRubyObject table, IRubyObject schema) {
        if ( table.isNil() ) {
            throw context.runtime.newArgumentError("nil table name");
        }
        final String tableName = table.toString();
        final String defaultSchema = schema.isNil() ? null : schema.toString();

        return tableExists(context, defaultSchema, tableName);
    }

    protected IRubyObject tableExists(final ThreadContext context,
        final String defaultSchema, final String tableName) {
        return withConnection(context, connection -> {
            final TableName components = extractTableName(connection, defaultSchema, tableName);
            return context.runtime.newBoolean( tableExists(context, connection, components) );
        });
    }

    @JRubyMethod(name = {"columns", "columns_internal"}, required = 1, optional = 2)
    public RubyArray columns_internal(final ThreadContext context, final IRubyObject[] args)
        throws SQLException {
        return withConnection(context, connection -> {
            ResultSet columns = null;
            try {
                final String tableName = args[0].toString();
                // optionals (NOTE: catalog argumnet was never used before 1.3.0) :
                final String catalog = args.length > 1 ? toStringOrNull(args[1]) : null;
                final String defaultSchema = args.length > 2 ? toStringOrNull(args[2]) : null;

                final TableName components;
                components = extractTableName(connection, catalog, defaultSchema, tableName);

                if ( ! tableExists(context, connection, components) ) {
                    throw new SQLException("table: " + tableName + " does not exist");
                }

                final DatabaseMetaData metaData = connection.getMetaData();
                columns = metaData.getColumns(components.catalog, components.schema, components.name, null);
                return mapColumnsResult(context, metaData, components, columns);
            }
            finally {
                close(columns);
            }
        });
    }

    @JRubyMethod(name = "indexes")
    public IRubyObject indexes(final ThreadContext context, IRubyObject tableName, IRubyObject name) {
        return indexes(context, toStringOrNull(tableName), toStringOrNull(name), null);
    }

    @JRubyMethod(name = "indexes")
    public IRubyObject indexes(final ThreadContext context, IRubyObject tableName, IRubyObject name, IRubyObject schemaName) {
        return indexes(context, toStringOrNull(tableName), toStringOrNull(name), toStringOrNull(schemaName));
    }

    // NOTE: metaData.getIndexInfo row mappings :
    protected static final int INDEX_INFO_TABLE_NAME = 3;
    protected static final int INDEX_INFO_NON_UNIQUE = 4;
    protected static final int INDEX_INFO_NAME = 6;
    protected static final int INDEX_INFO_COLUMN_NAME = 9;

    /**
     * Default JDBC introspection for index metadata on the JdbcConnection.
     *
     * JDBC index metadata is denormalized (multiple rows may be returned for
     * one index, one row per column in the index), so a simple block-based
     * filter like that used for tables doesn't really work here.  Callers
     * should filter the return from this method instead.
     */
    protected IRubyObject indexes(final ThreadContext context, final String tableName, final String name, final String schemaName) {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final Ruby runtime = context.runtime;
            final RubyClass IndexDefinition = getIndexDefinition(context);

            String _tableName = caseConvertIdentifierForJdbc(connection, tableName);
            String _schemaName = caseConvertIdentifierForJdbc(connection, schemaName);
            final TableName table = extractTableName(connection, null, _schemaName, _tableName);

            final List<RubyString> primaryKeys = primaryKeys(context, connection, table);

            ResultSet indexInfoSet = null;
            final RubyArray indexes = allocArray(context, 8);
            try {
                final DatabaseMetaData metaData = connection.getMetaData();
                indexInfoSet = metaData.getIndexInfo(table.catalog, table.schema, table.name, false, true);
                String currentIndex = null;

                while ( indexInfoSet.next() ) {
                    String indexName = indexInfoSet.getString(INDEX_INFO_NAME);
                    if ( indexName == null ) continue;
                    RubyArray currentColumns = null;

                    indexName = caseConvertIdentifierForRails(metaData, indexName);

                    final String columnName = indexInfoSet.getString(INDEX_INFO_COLUMN_NAME);
                    final RubyString rubyColumnName = cachedString(
                            context, caseConvertIdentifierForRails(metaData, columnName)
                    );
                    if ( primaryKeys.contains(rubyColumnName) ) continue;

                    // We are working on a new index
                    if ( ! indexName.equals(currentIndex) ) {
                        currentIndex = indexName;

                        String indexTableName = indexInfoSet.getString(INDEX_INFO_TABLE_NAME);
                        indexTableName = caseConvertIdentifierForRails(metaData, indexTableName);

                        final boolean nonUnique = indexInfoSet.getBoolean(INDEX_INFO_NON_UNIQUE);

                        IRubyObject[] args = new IRubyObject[] {
                            cachedString(context, indexTableName), // table_name
                            cachedString(context, indexName), // index_name
                            nonUnique ? context.fals : context.tru, // unique
                            currentColumns = allocArray(context, 4) // [] column names
                            // orders, (since AR 3.2) where, type, using (AR 4.0)
                        };

                        indexes.append(context, IndexDefinition.newInstance(context, args, Block.NULL_BLOCK)); // IndexDefinition.new
                    }

                    // one or more columns can be associated with an index
                    if ( currentColumns != null ) currentColumns.append(context, rubyColumnName);
                }

                return indexes;

            } finally { close(indexInfoSet); }
        });
    }

    protected RubyClass getIndexDefinition(final ThreadContext context) {
        var IDef = adapter.getMetaClass().getClass(context, "IndexDefinition");
        return IDef != null ? IDef : indexDefinition(context);
    }

    @JRubyMethod
    public IRubyObject foreign_keys(final ThreadContext context, IRubyObject table_name) {
        return foreignKeys(context, table_name.toString(), null, null);
    }

    protected IRubyObject foreignKeys(final ThreadContext context, final String tableName, final String schemaName, final String catalog) {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final Ruby runtime = context.runtime;
            final RubyClass FKDefinition = getForeignKeyDefinition(context);

            String _tableName = caseConvertIdentifierForJdbc(connection, tableName);
            String _schemaName = caseConvertIdentifierForJdbc(connection, schemaName);
            final TableName table = extractTableName(connection, catalog, _schemaName, _tableName);

            ResultSet fkInfoSet = null;
            final List<IRubyObject> fKeys = new ArrayList<>(8);
            try {
                final DatabaseMetaData metaData = connection.getMetaData();
                fkInfoSet = metaData.getImportedKeys(table.catalog, table.schema, table.name);

                while ( fkInfoSet.next() ) {
                    final RubyHash options = RubyHash.newHash(runtime);

                    String fkName = fkInfoSet.getString("FK_NAME");
                    if (fkName != null) {
                        fkName = caseConvertIdentifierForRails(metaData, fkName);
                        options.put(runtime.newSymbol("name"), fkName);
                    }

                    String columnName = fkInfoSet.getString("FKCOLUMN_NAME");
                    options.put(runtime.newSymbol("column"), caseConvertIdentifierForRails(metaData, columnName));

                    columnName = fkInfoSet.getString("PKCOLUMN_NAME");
                    options.put(runtime.newSymbol("primary_key"), caseConvertIdentifierForRails(metaData, columnName));

                    String fkTableName = fkInfoSet.getString("FKTABLE_NAME");
                    fkTableName = caseConvertIdentifierForRails(metaData, fkTableName);

                    String pkTableName = fkInfoSet.getString("PKTABLE_NAME");
                    pkTableName = caseConvertIdentifierForRails(metaData, pkTableName);

                    final String onDelete = extractForeignKeyRule( fkInfoSet.getInt("DELETE_RULE") );
                    if ( onDelete != null ) options.op_aset(context, runtime.newSymbol("on_delete"), runtime.newSymbol(onDelete));

                    final String onUpdate = extractForeignKeyRule( fkInfoSet.getInt("UPDATE_RULE") );
                    if ( onUpdate != null ) options.op_aset(context, runtime.newSymbol("on_update"), runtime.newSymbol(onUpdate));

                    IRubyObject from_table = cachedString(context, fkTableName);
                    IRubyObject to_table = cachedString(context, pkTableName);
                    fKeys.add( FKDefinition.newInstance(context, from_table, to_table, options, Block.NULL_BLOCK) ); // ForeignKeyDefinition.new
                }

                return newArray(context, fKeys);

            } finally { close(fkInfoSet); }
        });
    }

    protected String extractForeignKeyRule(final int rule) {
        switch (rule) {
            case DatabaseMetaData.importedKeyNoAction :  return null ;
            case DatabaseMetaData.importedKeyCascade :   return "cascade" ;
            case DatabaseMetaData.importedKeySetNull :   return "nullify" ;
            case DatabaseMetaData.importedKeySetDefault: return "default" ;
        }
        return null;
    }

    protected RubyClass getForeignKeyDefinition(final ThreadContext context) {
        final RubyClass adapterClass = adapter.getMetaClass();
        var FKDef = adapterClass.getClass(context, "ForeignKeyDefinition");
        return FKDef != null ? FKDef : foreignKeyDefinition(context);
    }


    @JRubyMethod(name = "supports_foreign_keys?")
    public IRubyObject supports_foreign_keys_p(final ThreadContext context) throws SQLException {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final DatabaseMetaData metaData = connection.getMetaData();
            return context.runtime.newBoolean( metaData.supportsIntegrityEnhancementFacility() );
        });
    }

    @JRubyMethod(name = "supports_views?")
    public IRubyObject supports_views_p(final ThreadContext context) throws SQLException {
        return withConnection(context, (Callable<IRubyObject>) connection -> {
            final DatabaseMetaData metaData = connection.getMetaData();
            final ResultSet tableTypes = metaData.getTableTypes();
            try {
                while ( tableTypes.next() ) {
                    if ( "VIEW".equalsIgnoreCase( tableTypes.getString(1) ) ) {
                        return context.runtime.newBoolean( true );
                    }
                }
            }
            finally {
                close(tableTypes);
            }
            return context.runtime.newBoolean( false );
        });
    }

    @JRubyMethod(name = "with_jdbc_connection", alias = "with_connection_retry_guard", frame = true)
    public IRubyObject with_jdbc_connection(final ThreadContext context, final Block block) {
        return withConnection(context, connection -> block.call(context, convertJavaToRuby(connection)));
    }

    /*
     * (binary?, column_name, table_name, id_key, id_value, value)
     */
    @Deprecated
    @JRubyMethod(name = "write_large_object", required = 6)
    public IRubyObject write_large_object(final ThreadContext context, final IRubyObject[] args)
        throws SQLException {

        final boolean binary = args[0].isTrue();
        final String columnName = args[1].toString();
        final String tableName = args[2].toString();
        final String idKey = args[3].toString();
        final IRubyObject idVal = args[4];
        final IRubyObject lobValue = args[5];

        int count = updateLobValue(context, tableName, columnName, null, idKey, idVal, null, lobValue, binary);
        return context.runtime.newFixnum(count);
    }

    @JRubyMethod(name = "update_lob_value", required = 3)
    public IRubyObject update_lob_value(final ThreadContext context,
        final IRubyObject record, final IRubyObject column, final IRubyObject value)
        throws SQLException {

        final boolean binary = // column.type == :binary
            column.callMethod(context, "type").toString() == (Object) "binary";

        final IRubyObject recordClass = record.callMethod(context, "class");
        final IRubyObject adapter = recordClass.callMethod(context, "connection");

        IRubyObject columnName = column.callMethod(context, "name");
        columnName = adapter.callMethod(context, "quote_column_name", columnName);
        IRubyObject tableName = recordClass.callMethod(context, "table_name");
        tableName = adapter.callMethod(context, "quote_table_name", tableName);
        final IRubyObject idKey = recordClass.callMethod(context, "primary_key"); // 'id'
        // callMethod(context, "quote", primaryKey);
        final IRubyObject idColumn = // record.class.columns_hash['id']
            recordClass.callMethod(context, "columns_hash").callMethod(context, "[]", idKey);

        final IRubyObject id = record.callMethod(context, "id"); // record.id

        final int count = updateLobValue(context,
            tableName.toString(), columnName.toString(), column,
            idKey.toString(), id, idColumn, value, binary
        );
        return context.runtime.newFixnum(count);
    }

    private int updateLobValue(final ThreadContext context,
        final String tableName, final String columnName, final IRubyObject column,
        final String idKey, final IRubyObject idValue, final IRubyObject idColumn,
        final IRubyObject value, final boolean binary) {

        final String sql = "UPDATE "+ tableName +" SET "+ columnName +" = ? WHERE "+ idKey +" = ?" ;

        // TODO: Fix this, the columns don't have the info needed to handle this anymore
        //       currently commented out so that it will compile

        return withConnection(context, connection -> {
            PreparedStatement statement = null;
            try {
                statement = connection.prepareStatement(sql);
                /*
                if ( binary ) { // blob
                    setBlobParameter(context, connection, statement, 1, value, column, Types.BLOB);
                }
                else { // clob
                    setClobParameter(context, connection, statement, 1, value, column, Types.CLOB);
                }
                setStatementParameter(context, context.runtime, connection, statement, 2, idValue, idColumn);
                */
                return (Integer) statement.executeUpdate();
            }
            finally { close(statement); }
        });
    }

    protected String caseConvertIdentifierForRails(final Connection connection, final String value)
        throws SQLException {
        if ( value == null ) return null;
        return caseConvertIdentifierForRails(connection.getMetaData(), value);
    }

    /**
     * Convert an identifier coming back from the database to a case which Rails is expecting.
     *
     * Assumption: Rails identifiers will be quoted for mixed or will stay mixed
     * as identifier names in Rails itself.  Otherwise, they expect identifiers to
     * be lower-case.  Databases which store identifiers uppercase should be made
     * lower-case.
     *
     * Assumption 2: It is always safe to convert all upper case names since it appears that
     * some adapters do not report StoresUpper/Lower/Mixed correctly (am I right postgres/mysql?).
     */
    protected static String caseConvertIdentifierForRails(final DatabaseMetaData metaData, final String value)
        throws SQLException {
        if ( value == null ) return null;
        return metaData.storesUpperCaseIdentifiers() ? value.toLowerCase() : value;
    }

    protected String caseConvertIdentifierForJdbc(final Connection connection, final String value)
        throws SQLException {
        if ( value == null ) return null;
        return caseConvertIdentifierForJdbc(connection.getMetaData(), value);
    }

    /**
     * Convert an identifier destined for a method which cares about the databases internal
     * storage case.  Methods like DatabaseMetaData.getPrimaryKeys() needs the table name to match
     * the internal storage name.  Arbitrary queries and the like DO NOT need to do this.
     */
    protected static String caseConvertIdentifierForJdbc(final DatabaseMetaData metaData, final String value)
        throws SQLException {
        if ( value == null ) return null;

        if ( metaData.storesUpperCaseIdentifiers() ) {
            return value.toUpperCase();
        }
        else if ( metaData.storesLowerCaseIdentifiers() ) {
            return value.toLowerCase();
        }
        return value;
    }

    // internal helper exported on ArJdbc @JRubyMethod(meta = true)
    public static IRubyObject with_meta_data_from_data_source_if_any(final ThreadContext context,
        final IRubyObject self, final IRubyObject config, final Block block) {
        final IRubyObject ds_or_name = rawDataSourceOrName(context, config);

        if ( ds_or_name == null ) return context.fals;

        final javax.sql.DataSource dataSource;
        final Object dsOrName = ds_or_name.toJava(Object.class);
        if ( dsOrName instanceof javax.sql.DataSource ) {
            dataSource = (javax.sql.DataSource) dsOrName;
        }
        else {
            try {
                dataSource = (javax.sql.DataSource) getInitialContext().lookup( dsOrName.toString() );
            }
            catch (Exception e) { // javax.naming.NamingException
                throw wrapException(context, context.runtime.getRuntimeError(), e);
            }
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            final DatabaseMetaData metaData = connection.getMetaData();
            return block.call(context, JavaUtil.convertJavaToRuby(context.runtime, metaData));
        }
        catch (SQLException e) {
            throw wrapSQLException(context, getJDBCError(context), e, null);
        }
        finally { close(connection); }
    }

    @JRubyMethod(name = "jndi_config?", meta = true)
    public static IRubyObject jndi_config_p(final ThreadContext context,
        final IRubyObject self, final IRubyObject config) {
        return context.runtime.newBoolean( isJndiConfig(context, config) );
    }

    private static IRubyObject rawDataSourceOrName(final ThreadContext context, final IRubyObject config) {
        // config[:jndi] || config[:data_source]

        final Ruby runtime = context.runtime;

        IRubyObject configValue;

        if ( config.getClass() == RubyHash.class ) { // "optimized" version
            final RubyHash configHash = ((RubyHash) config);
            configValue = configHash.fastARef(runtime.newSymbol("jndi"));
            if ( configValue == null ) {
                configValue = configHash.fastARef(runtime.newSymbol("data_source"));
            }
        }
        else {
            configValue = config.callMethod(context, "[]", runtime.newSymbol("jndi"));
            if ( configValue == context.nil ) configValue = null;
            if ( configValue == null ) {
                configValue = config.callMethod(context, "[]", runtime.newSymbol("data_source"));
            }
        }

        if ( configValue == null || configValue == context.nil || configValue == context.fals ) {
            return null;
        }
        return configValue;
    }

    private static boolean isJndiConfig(final ThreadContext context, final IRubyObject config) {
        return rawDataSourceOrName(context, config) != null;
    }

    @JRubyMethod(name = "jndi_lookup", meta = true)
    public static IRubyObject jndi_lookup(final ThreadContext context,
                                          final IRubyObject self, final IRubyObject name) {
        try {
            final Object bound = getInitialContext().lookup( name.toString() );
            return JavaUtil.convertJavaToRuby(context.runtime, bound);
        }
        catch (Exception e) { // javax.naming.NamingException
            if ( e instanceof RaiseException ) throw (RaiseException) e;
            throw wrapException(context, context.runtime.getNameError(), e);
        }
    }

    private ConnectionFactory setDriverFactory(final ThreadContext context) {

        final IRubyObject url = getConfigValue(context, "url");
        final IRubyObject driver = getConfigValue(context, "driver");
        final IRubyObject username = getConfigValue(context, "username");
        final IRubyObject password = getConfigValue(context, "password");

        final IRubyObject driver_instance = getConfigValue(context, "driver_instance");

        if ( url.isNil() || ( driver.isNil() && driver_instance.isNil() ) ) {
            final RubyClass errorClass = getConnectionNotEstablished(context);
            throw context.runtime.newRaiseException(errorClass, "adapter requires :driver class and jdbc :url");
        }

        final String jdbcURL = buildURL(context, url);
        final ConnectionFactory factory;

        if ( driver_instance != null && ! driver_instance.isNil() ) {
            final Object driverInstance = driver_instance.toJava(Object.class);
            if ( driverInstance instanceof DriverWrapper ) {
                setConnectionFactory(factory = new DriverConnectionFactory(
                        (DriverWrapper) driverInstance, jdbcURL,
                        ( username.isNil() ? null : username.toString() ),
                        ( password.isNil() ? null : password.toString() )
                ));
                return factory;
            }
            else {
                setConnectionFactory(factory = new RubyConnectionFactory(
                        driver_instance, context.runtime.newString(jdbcURL),
                        ( username.isNil() ? username : username.asString() ),
                        ( password.isNil() ? password : password.asString() )
                ));
                return factory;
            }
        }

        final String user = username.isNil() ? null : username.toString();
        final String pass = password.isNil() ? null : password.toString();

        final DriverWrapper driverWrapper = newDriverWrapper(context, driver.toString());
        setConnectionFactory(factory = new DriverConnectionFactory(driverWrapper, jdbcURL, user, pass));
        return factory;
    }

    protected DriverWrapper newDriverWrapper(final ThreadContext context, final String driver) throws RaiseException {
        try {
            return new DriverWrapper(context.runtime, driver, resolveDriverProperties(context));
        }
        //catch (ClassNotFoundException e) {
        //    throw wrapException(context, context.runtime.getNameError(), e, "cannot load driver class " + driver);
        //}
        catch (ExceptionInInitializerError e) {
            throw wrapException(context, context.runtime.getNameError(), e, "cannot initialize driver class " + driver);
        }
        catch (LinkageError e) {
            throw wrapException(context, context.runtime.getNameError(), e, "cannot link driver class " + driver);
        }
        catch (ClassCastException e) {
            throw wrapException(context, context.runtime.getNameError(), e);
        }
        catch (IllegalAccessException e) { throw wrapException(context, e); }
        catch (InstantiationException e) {
            throw wrapException(context, e.getCause() != null ? e.getCause() : e);
        }
        catch (SecurityException e) {
            throw wrapException(context, context.runtime.getSecurityError(), e);
        } catch (InvocationTargetException e) {
            throw wrapException(context, context.runtime.getNameError(), e, "invalid invocation target for " + driver);
        } catch (NoSuchMethodException e) {
            throw wrapException(context, context.runtime.getNameError(), e, "cannot find constructor for " + driver);
        }
    }

    @Deprecated // no longer used - only kept for API compatibility
    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject jdbc_url(final ThreadContext context) {
        final IRubyObject url = getConfigValue(context, "url");
        return context.runtime.newString( buildURL(context, url) );
    }

    protected String buildURL(final ThreadContext context, final IRubyObject url) {
        IRubyObject options = getConfigValue(context, "options");
        if ( options == context.nil ) options = null;
        // NOTE: else should print a deprecation warning - should use properties: instead
        return DriverWrapper.buildURL(url, (Map) options);
    }

    private Properties resolveDriverProperties(final ThreadContext context) {
        IRubyObject properties = getConfigValue(context, "properties");
        if ( properties == context.nil ) return null;
        Map<?, ?> propertiesJava = (Map) properties.toJava(Map.class);
        if ( propertiesJava instanceof Properties ) {
            return (Properties) propertiesJava;
        }
        final Properties props = new Properties();
        for ( Map.Entry entry : propertiesJava.entrySet() ) {
            props.setProperty(entry.getKey().toString(), entry.getValue().toString());
        }
        return props;
    }

    private ConnectionFactory setDataSourceFactory(final ThreadContext context) {
        final javax.sql.DataSource dataSource; final String lookupName;
        IRubyObject value = getConfigValue(context, "data_source");
        if ( value == context.nil ) {
            value = getConfigValue(context, "jndi");
            lookupName = value.toString();
            dataSource = DataSourceConnectionFactory.lookupDataSource(context, lookupName);
        }
        else {
            dataSource = (javax.sql.DataSource) value.toJava(javax.sql.DataSource.class);
            lookupName = null;
        }
        ConnectionFactory factory = new DataSourceConnectionFactory(dataSource, lookupName);
        setConnectionFactory(factory);
        return factory;
    }

    private static transient IRubyObject defaultConfig;
    private static volatile boolean defaultConfigJndi;
    private static transient ConnectionFactory defaultConnectionFactory;

    /**
     * @return whether the connection factory is JNDI based
     */
    private boolean setupConnectionFactory(final ThreadContext context) {
        if ( defaultConfig == null ) {
            synchronized(RubyJdbcConnection.class) {
                if ( defaultConfig == null ) {
                    final boolean jndi = isJndiConfig(context, config);
                    if ( jndi ) {
                        defaultConnectionFactory = setDataSourceFactory(context);
                    }
                    else {
                        defaultConnectionFactory = setDriverFactory(context);
                    }
                    defaultConfigJndi = jndi; defaultConfig = config;
                    return jndi;
                }
            }
        }

        if ( defaultConfig != null && ( defaultConfig == config || defaultConfig.eql(config) ) ) {
            setConnectionFactory( defaultConnectionFactory );
            return defaultConfigJndi;
        }

        if ( isJndiConfig(context, config) ) {
            setDataSourceFactory(context); return true;
        }
        else {
            setDriverFactory(context); return false;
        }
    }

    @JRubyMethod(name = "jndi?", alias = "jndi_connection?")
    public RubyBoolean jndi_p(final ThreadContext context) {
        return context.runtime.newBoolean(jndi);
    }

    protected boolean isJndi() { return this.jndi; }

    @JRubyMethod(name = "config")
    public IRubyObject config() { return config; }

    public IRubyObject getConfig() { return this.config; }

    protected final IRubyObject getConfigValue(final ThreadContext context, final String key) {
        final RubySymbol keySym = context.runtime.newSymbol(key);
        if ( config instanceof RubyHash ) {
            final IRubyObject value = ((RubyHash) config).fastARef(keySym);
            return value == null ? context.nil : value;
        }
        return config.callMethod(context, "[]", keySym);
    }

    protected final IRubyObject setConfigValue(final ThreadContext context,
                                               final String key, final IRubyObject value) {
        final RubySymbol keySym = context.runtime.newSymbol(key);
        if ( config instanceof RubyHash ) {
            return ((RubyHash) config).op_aset(context, keySym, value);
        }
        return config.callMethod(context, "[]=", new IRubyObject[] { keySym, value });
    }

    protected final IRubyObject setConfigValueIfNotSet(final ThreadContext context,
                                                       final String key, final IRubyObject value) {
        final RubySymbol keySym = context.runtime.newSymbol(key);
        if ( config instanceof RubyHash ) {
            final IRubyObject setValue = ((RubyHash) config).fastARef(keySym);
            if ( setValue != null ) return setValue;
            return ((RubyHash) config).op_aset(context, keySym, value);
        }

        final IRubyObject setValue = config.callMethod(context, "[]", keySym);
        if ( setValue != context.nil ) return setValue;
        return config.callMethod(context, "[]=", new IRubyObject[] { keySym, value });
    }

    private static String toStringOrNull(final IRubyObject arg) {
        return arg.isNil() ? null : arg.toString();
    }

    protected final IRubyObject getAdapter() { return this.adapter; }

    protected RubyClass getJdbcColumnClass(final ThreadContext context) {
        return (RubyClass) adapter.callMethod(context, "jdbc_column_class");
    }

    protected ConnectionFactory getConnectionFactory() throws RaiseException {
        if ( connectionFactory == null ) {
            // NOTE: only for (backwards) compatibility (to be deleted) :
            IRubyObject connection_factory = getInstanceVariable("@connection_factory");
            if ( connection_factory == null ) {
                throw getRuntime().newRuntimeError("@connection_factory not set");
            }
            connectionFactory = (ConnectionFactory) connection_factory.toJava(ConnectionFactory.class);
        }
        return connectionFactory;
    }

    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    protected Connection newConnection() throws SQLException {
        return getConnectionFactory().newConnection();
    }

    private static String[] getTypes(final IRubyObject typeArg) {
        if ( typeArg instanceof RubyArray ) {
            final RubyArray typesArr = (RubyArray) typeArg;
            final String[] types = new String[typesArr.size()];
            for ( int i = 0; i < types.length; i++ ) {
                types[i] = typesArr.eltInternal(i).toString();
            }
            return types;
        }
        return new String[] { typeArg.toString() }; // expect a RubyString
    }

    /**
     * Maps a query result into a <code>ActiveRecord</code> result.
     * @param context
     * @param connection
     * @param resultSet
     * @param columns
     * @return expected to return a <code>ActiveRecord::Result</code>
     * @throws SQLException
     */
    protected IRubyObject mapToResult(final ThreadContext context, final Connection connection,
                                      final ResultSet resultSet, final ColumnData[] columns) throws SQLException {
        final Ruby runtime = context.runtime;

        final RubyArray resultRows = newArray(context);

        while (resultSet.next()) {
            resultRows.append(context, mapRow(context, runtime, columns, resultSet, this));
        }

        return newResult(context, columns, resultRows);
    }

    protected IRubyObject jdbcToRuby(
        final ThreadContext context, final Ruby runtime,
        final int column, final int type, final ResultSet resultSet)
        throws SQLException {

        try {
            switch (type) {
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return streamToRuby(context, runtime, resultSet, column);
            case Types.CLOB:
            case Types.NCLOB: // JDBC 4.0
                return readerToRuby(context, runtime, resultSet, column);
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR: // JDBC 4.0
                return readerToRuby(context, runtime, resultSet, column);
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                return integerToRuby(context, runtime, resultSet, column);
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
                return doubleToRuby(context, runtime, resultSet, column);
            case Types.BIGINT:
                return bigIntegerToRuby(context, runtime, resultSet, column);
            case Types.NUMERIC:
            case Types.DECIMAL:
                return decimalToRuby(context, runtime, resultSet, column);
            case Types.DATE:
                return dateToRuby(context, runtime, resultSet, column);
            case Types.TIME:
                return timeToRuby(context, runtime, resultSet, column);
            case Types.TIMESTAMP:
                return timestampToRuby(context, runtime, resultSet, column);
            case Types.BIT:
                return bitToRuby(context, runtime, resultSet, column);
            case Types.BOOLEAN:
                return booleanToRuby(context, runtime, resultSet, column);
            case Types.SQLXML: // JDBC 4.0
                return xmlToRuby(context, runtime, resultSet, column);
            case Types.ARRAY: // we handle JDBC Array into (Ruby) []
                return arrayToRuby(context, runtime, resultSet, column);
            case Types.NULL:
                return context.nil;
            // NOTE: (JDBC) exotic stuff just cause it's so easy with JRuby :)
            case Types.JAVA_OBJECT:
            case Types.OTHER:
                return objectToRuby(context, runtime, resultSet, column);
            // (default) String
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR: // JDBC 4.0
            case Types.NVARCHAR: // JDBC 4.0
            default:
                return stringToRuby(context, runtime, resultSet, column);
            }
            // NOTE: not mapped types :
            //case Types.DISTINCT:
            //case Types.STRUCT:
            //case Types.REF:
            //case Types.DATALINK:
        }
        catch (IOException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /**
     * Converts an integer column into a Ruby integer.
     * @param context current thread context
     * @param runtime current thread context
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyInteger if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject integerToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final long value = resultSet.getLong(column);
        if ( value == 0 && resultSet.wasNull() ) return context.nil;
        return runtime.newFixnum(value);
    }

    /**
     * Converts an double column into a Ruby integer.
     * @param context current thread context
     * @param runtime the ruby runtime
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyInteger if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject doubleToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final double value = resultSet.getDouble(column);
        if ( value == 0 && resultSet.wasNull() ) return context.nil;
        return runtime.newFloat(value);
    }

    /**
     * Converts a string column into a Ruby string
     * @param context current thread context
     * @param runtime the ruby runtime
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyString if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject stringToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column) throws SQLException {
        final String value = resultSet.getString(column);
        if ( value == null ) return context.nil;
        return newDefaultInternalString(runtime, value);
    }

    protected static IRubyObject bytesToRubyString(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException { // optimized String -> byte[]
        final byte[] value = resultSet.getBytes(column);
        if ( value == null ) return context.nil;
        return newDefaultInternalString(runtime, value);
    }

    protected IRubyObject bigIntegerToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column) throws SQLException {
        final String value = resultSet.getString(column);
        if ( value == null ) return context.nil;
        return RubyBignum.bignorm(runtime, new BigInteger(value));
    }

    protected IRubyObject decimalToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column) throws SQLException {
        final BigDecimal value = resultSet.getBigDecimal(column);
        if ( value == null ) return context.nil;
        return new org.jruby.ext.bigdecimal.RubyBigDecimal(runtime, value);
    }

    protected static Boolean rawDateTime;
    static {
        final String dateTimeRaw = SafePropertyAccessor.getProperty("arjdbc.datetime.raw");
        if ( dateTimeRaw != null ) {
            rawDateTime = (Boolean) Boolean.parseBoolean(dateTimeRaw);
        }
        // NOTE: we do this since it will have a different value depending on
        // AR version - since 4.0 false by default otherwise will be true ...
    }

    @JRubyMethod(name = "raw_date_time?", meta = true)
    public static IRubyObject useRawDateTime(final ThreadContext context, final IRubyObject self) {
        if ( rawDateTime == null ) return context.nil;
        return context.runtime.newBoolean(rawDateTime);
    }

    @JRubyMethod(name = "raw_date_time=", meta = true)
    public static IRubyObject setRawDateTime(final IRubyObject self, final IRubyObject value) {
        if ( value instanceof RubyBoolean ) {
            rawDateTime = (Boolean) value.isTrue();
        }
        else {
            rawDateTime = value.isNil() ? null : Boolean.TRUE;
        }
        return value;
    }

    protected IRubyObject dateToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {

        final Date value = resultSet.getDate(column);
        if ( value == null ) {
            // FIXME: Do we really need this wasNull check here?
            return resultSet.wasNull() ? context.nil : RubyString.newEmptyString(runtime);
        }

        if ( rawDateTime != null && rawDateTime) {
            return RubyString.newString(runtime, DateTimeUtils.dateToString(value));
        }

        return DateTimeUtils.newDateAsTime(context, value, DateTimeZone.UTC).callMethod(context, "to_date");
    }

    protected IRubyObject timeToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {

        final Time value = resultSet.getTime(column);
        if ( value == null ) {
            return resultSet.wasNull() ? context.nil : RubyString.newEmptyString(runtime);
        }

        if ( rawDateTime != null && rawDateTime) {
            return RubyString.newString(runtime, DateTimeUtils.timeToString(value));
        }

        return DateTimeUtils.newDummyTime(context, value, getDefaultTimeZone(context));
    }

    protected IRubyObject timestampToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {

        final Timestamp value = resultSet.getTimestamp(column);
        if ( value == null ) {
            return resultSet.wasNull() ? context.nil : RubyString.newEmptyString(runtime);
        }

        if ( rawDateTime != null && rawDateTime) {
            return RubyString.newString(runtime, DateTimeUtils.timestampToString(value));
        }

        // NOTE: with 'raw' String AR's Type::DateTime does put the time in proper time-zone
        // while when returning a Time object it just adjusts usec (apply_seconds_precision)
        // yet for custom SELECTs to work (SELECT created_at ... ) and for compatibility we
        // should be returning Time (by default) - AR does this by adjusting mysql2/pg returns

        return DateTimeUtils.newTime(context, value, getDefaultTimeZone(context));
    }

    protected static Boolean rawBoolean;
    static {
        final String booleanRaw = SafePropertyAccessor.getProperty("arjdbc.boolean.raw");
        if ( booleanRaw != null ) {
            rawBoolean = (Boolean) Boolean.parseBoolean(booleanRaw);
        }
    }

    @JRubyMethod(name = "raw_boolean?", meta = true)
    public static IRubyObject useRawBoolean(final ThreadContext context, final IRubyObject self) {
        if ( rawBoolean == null ) return context.nil;
        return context.runtime.newBoolean(rawBoolean);
    }

    @JRubyMethod(name = "raw_boolean=", meta = true)
    public static IRubyObject setRawBoolean(final IRubyObject self, final IRubyObject value) {
        if ( value instanceof RubyBoolean ) {
            rawBoolean = (Boolean) value.isTrue();
        }
        else {
            rawBoolean = value.isNil() ? null : Boolean.TRUE;
        }
        return value;
    }

    /**
     * Converts a bit column to its Ruby equivalent.
     * Defaults to treating it as a boolean value.
     * @param context current thread context
     * @param runtime current instance of Ruby.
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyBoolean if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject bitToRuby(final ThreadContext context, final Ruby runtime, final ResultSet resultSet,
                                    int column) throws SQLException {
        return booleanToRuby(context, runtime, resultSet, column);
    }

    protected IRubyObject booleanToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        if ( rawBoolean != null && rawBoolean) {
            final String value = resultSet.getString(column);
            if ( value == null /* && resultSet.wasNull() */ ) return context.nil;
            return RubyString.newUnicodeString(runtime, value);
        }
        final boolean value = resultSet.getBoolean(column);
        if (!value && resultSet.wasNull()) return context.nil;
        return runtime.newBoolean(value);
    }

    protected static int streamBufferSize = 1024;

    protected IRubyObject streamToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException, IOException {
        final InputStream stream = resultSet.getBinaryStream(column);

        if (stream == null) return context.nil;

        try {

            final int buffSize = streamBufferSize;
            final ByteList bytes = new ByteList(buffSize);

            readBytes(bytes, stream, buffSize);

            return runtime.newString(bytes);

        } finally {
            stream.close();
        }
    }

    /**
     * Converts a column that is handled as a Reader object into a Ruby string
     * @param context current thread context
     * @param runtime the ruby runtime
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyString if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject readerToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException, IOException {
        final Reader reader = resultSet.getCharacterStream(column);
        try {
            if ( reader == null ) return context.nil;

            final int bufSize = streamBufferSize;
            final StringBuilder string = new StringBuilder(bufSize);

            final char[] buf = new char[bufSize];
            for (int len = reader.read(buf); len != -1; len = reader.read(buf)) {
                string.append(buf, 0, len);
            }

            return newDefaultInternalString(runtime, string);
        }
        finally { if ( reader != null ) reader.close(); }
    }


    /**
     * Converts the column into a RubyObject
     * @param context current thread context
     * @param runtime the ruby runtime
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyObject if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject objectToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final Object value = resultSet.getObject(column);

        if ( value == null ) return context.nil;

        return JavaUtil.convertJavaToRuby(runtime, value);
    }

    protected IRubyObject arrayToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final Array value = resultSet.getArray(column);
        try {
            if ( value == null ) return context.nil;

            final RubyArray array = newArray(context);

            final ResultSet arrayResult = value.getResultSet(); // 1: index, 2: value
            final int baseType = value.getBaseType();

            if (baseType == Types.OTHER) {
                /*
                 * If the base type is other, we may not have enough
                 * information to correctly convert it so return it
                 * as a string so it can be parsed on the Ruby side.
                 * If we send it back as an array, AR assumes it has already
                 * been parsed and doesn't try to cast the values inside the array.
                 * This is primarly due to not being able to recognize json
                 * strings in postgres but would apply to any custom type that couldn't be converted.
                 * This won't work for multi-dimensional arrays of type other, but since
                 * we currently don't support them that shouldn't be a problem.
                 */
                return stringToRuby(context, runtime, resultSet, column);
            }

            while ( arrayResult.next() ) {
                array.append(context, jdbcToRuby(context, runtime, 2, baseType, arrayResult));
            }
            arrayResult.close();

            return array;
        }
        finally { if ( value != null ) value.free(); }
    }

    /**
     * Converts an XML column into a Ruby string
     * @param context current thread context
     * @param runtime the ruby runtime
     * @param resultSet the jdbc result set to pull the value from
     * @param column the index of the column to convert
     * @return RubyNil if NULL or RubyString if there is a value
     * @throws SQLException if it failes to retrieve the value from the result set
     */
    protected IRubyObject xmlToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final SQLXML xml = resultSet.getSQLXML(column);
        try {
            if ( xml == null ) return context.nil;

            return RubyString.newInternalFromJavaExternal(runtime, xml.getString());
        }
        finally { if ( xml != null ) xml.free(); }
    }

    protected void setStatementParameters(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final RubyArray binds) throws SQLException {

        for ( int i = 0; i < binds.getLength(); i++ ) {
            setStatementParameter(context, connection, statement, i + 1, binds.eltInternal(i));
        }
    }

    // Set the prepared statement attributes based on the passed in Attribute object
    protected void setStatementParameter(final ThreadContext context,
            final Connection connection, final PreparedStatement statement,
            final int index, IRubyObject attribute) throws SQLException {

        final IRubyObject value;
        final int type;

        if (attributeClass.isInstance(attribute)) {
            type = jdbcTypeForAttribute(context, attribute);
            value = valueForDatabase(context, attribute);
        } else if (timeZoneClass.isInstance(attribute)) {
            type = jdbcTypeFor("timestamp");
            value = attribute;
        } else {
            type = jdbcTypeForPrimitiveAttribute(context, attribute);
            value = attribute;
        }

        // All the set methods were calling this first so save a method call in the nil case
        if ( value == context.nil ) {
            statement.setNull(index, type);
            return;
        }

        switch (type) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                setIntegerParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.BIGINT:
                setBigIntegerParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
                setDoubleParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.NUMERIC:
            case Types.DECIMAL:
                setDecimalParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.DATE:
                setDateParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.TIME:
                setTimeParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.TIMESTAMP:
                setTimestampParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.BIT:
            case Types.BOOLEAN:
                setBooleanParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.SQLXML:
                setXmlParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.ARRAY:
                setArrayParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.JAVA_OBJECT:
            case Types.OTHER:
                setObjectParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                setBlobParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.CLOB:
            case Types.NCLOB: // JDBC 4.0
                setClobParameter(context, connection, statement, index, value, attribute, type);
                break;
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR: // JDBC 4.0
            case Types.NVARCHAR: // JDBC 4.0
            default:
                setStringParameter(context, connection, statement, index, value, attribute, type);
        }
    }

    protected static final Map<String, Integer> JDBC_TYPE_FOR = new HashMap<>(32, 1);
    static {
        JDBC_TYPE_FOR.put("string", Integer.valueOf(Types.VARCHAR));
        JDBC_TYPE_FOR.put("text", Integer.valueOf(Types.CLOB));
        JDBC_TYPE_FOR.put("integer", Integer.valueOf(Types.INTEGER));
        JDBC_TYPE_FOR.put("float", Integer.valueOf(Types.FLOAT));
        JDBC_TYPE_FOR.put("real", Integer.valueOf(Types.REAL));
        JDBC_TYPE_FOR.put("decimal", Integer.valueOf(Types.DECIMAL));
        JDBC_TYPE_FOR.put("date", Integer.valueOf(Types.DATE));
        JDBC_TYPE_FOR.put("time", Integer.valueOf(Types.TIME));
        JDBC_TYPE_FOR.put("datetime", Integer.valueOf(Types.TIMESTAMP));
        JDBC_TYPE_FOR.put("timestamp", Integer.valueOf(Types.TIMESTAMP));
        JDBC_TYPE_FOR.put("boolean", Integer.valueOf(Types.BOOLEAN));
        JDBC_TYPE_FOR.put("array", Integer.valueOf(Types.ARRAY));
        JDBC_TYPE_FOR.put("xml", Integer.valueOf(Types.SQLXML));

        // also mapping standard SQL names :
        JDBC_TYPE_FOR.put("bit", Integer.valueOf(Types.BIT));
        JDBC_TYPE_FOR.put("tinyint", Integer.valueOf(Types.TINYINT));
        JDBC_TYPE_FOR.put("smallint", Integer.valueOf(Types.SMALLINT));
        JDBC_TYPE_FOR.put("bigint", Integer.valueOf(Types.BIGINT));
        JDBC_TYPE_FOR.put("int", Integer.valueOf(Types.INTEGER));
        JDBC_TYPE_FOR.put("double", Integer.valueOf(Types.DOUBLE));
        JDBC_TYPE_FOR.put("numeric", Integer.valueOf(Types.NUMERIC));
        JDBC_TYPE_FOR.put("char", Integer.valueOf(Types.CHAR));
        JDBC_TYPE_FOR.put("varchar", Integer.valueOf(Types.VARCHAR));
        JDBC_TYPE_FOR.put("binary", Integer.valueOf(Types.BINARY));
        JDBC_TYPE_FOR.put("varbinary", Integer.valueOf(Types.VARBINARY));
        //JDBC_TYPE_FOR.put("struct", Types.STRUCT);
        JDBC_TYPE_FOR.put("blob", Integer.valueOf(Types.BLOB));
        JDBC_TYPE_FOR.put("clob", Integer.valueOf(Types.CLOB));
        JDBC_TYPE_FOR.put("nchar", Integer.valueOf(Types.NCHAR));
        JDBC_TYPE_FOR.put("nvarchar", Integer.valueOf(Types.NVARCHAR));
        JDBC_TYPE_FOR.put("nclob", Integer.valueOf(Types.NCLOB));
    }

    protected int jdbcTypeForAttribute(final ThreadContext context,
        final IRubyObject attribute) throws SQLException {

        final String internedType = internedTypeFor(context, attribute);
        final Integer sqlType = jdbcTypeFor(internedType);
        if ( sqlType != null ) {
            return sqlType;
        }

        return Types.OTHER; // -1 as well as 0 are used in Types
    }

    protected String internedTypeForPrimitive(final ThreadContext context, final IRubyObject value) throws SQLException {
        if (value instanceof RubyString) {
            return "string";
        }
        if (value instanceof RubyInteger) {
            return "integer";
        }
        if (value instanceof RubyNumeric) {
            return "float";
        }
        if (value instanceof RubyTime || value instanceof RubyDateTime) {
            return "timestamp";
        }
        if (value instanceof RubyDate) {
            return "date";
        }
        if (value instanceof RubyBoolean) {
            return "boolean";
        }
        return "string";
    }

    protected Integer jdbcTypeForPrimitiveAttribute(final ThreadContext context,
                                                    final IRubyObject attribute) throws SQLException {
        final String internedType = internedTypeForPrimitive(context, attribute);
        return jdbcTypeFor(internedType);
    }

    protected Integer jdbcTypeFor(final String type) {
        return JDBC_TYPE_FOR.get(type);
    }

    // ActiveRecord::Attribute#type (mostly sub-classes e.g. ActiveRecord::Attribute::WithCastValue)
    protected static IRubyObject attributeType(final ThreadContext context, final IRubyObject attribute) {
        // NOTE: a piece of (premature) optimalization - cause we can and AR 5.x does not mind
        return ((RubyBasicObject) attribute).getInstanceVariable("@type"); // attribute.callMethod(context, "type");
    }

    protected static IRubyObject attributeSQLType(final ThreadContext context, final IRubyObject attribute) {
        final IRubyObject type = attributeType(context, attribute);
        if (type != null) return type.callMethod(context, "type");
        return context.nil;
    }

    private final CachingCallSite value_site = new FunctionalCachingCallSite("value"); // AR::Attribute#value

    protected String internedTypeFor(final ThreadContext context, final IRubyObject attribute) throws SQLException {

        final IRubyObject type = attributeSQLType(context, attribute);

        if ( type != context.nil ) return type.asJavaString();

        final IRubyObject value = value_site.call(context, attribute, attribute);

        return internedTypeForPrimitive(context, value);
    }

    protected final RubyTime timeInDefaultTimeZone(final ThreadContext context, final IRubyObject value) {
        return timeInDefaultTimeZone(context, DateTimeUtils.toTime(context, value));
    }

    protected final RubyTime timeInDefaultTimeZone(final ThreadContext context, final RubyTime time) {
        final DateTimeZone defaultZone = getDefaultTimeZone(context);
        if (defaultZone == time.getDateTime().getZone()) return time;
        final DateTime adjustedDateTime = time.getDateTime().withZone(defaultZone);
        final RubyTime timeInDefaultTZ = new RubyTime(context.runtime, context.runtime.getTime(), adjustedDateTime);
        timeInDefaultTZ.setNSec(time.getNSec());
        return timeInDefaultTZ;
    }

    protected final DateTime dateTimeInDefaultTimeZone(final ThreadContext context, final DateTime dateTime) {
        final DateTimeZone defaultZone = getDefaultTimeZone(context);
        if (defaultZone == dateTime.getZone()) return dateTime;
        return dateTime.withZone(defaultZone);
    }

    public static RubyTime toTime(final ThreadContext context, final IRubyObject value) {
        return DateTimeUtils.toTime(context, value);
    }

    protected boolean isDefaultTimeZoneUTC(final ThreadContext context) {
        return "utc".equalsIgnoreCase( default_timezone(context) );
    }

    protected DateTimeZone getDefaultTimeZone(final ThreadContext context) {
        return isDefaultTimeZoneUTC(context) ? DateTimeZone.UTC : getLocalTimeZone(context); // handles ENV['TZ']
    }

    private String default_timezone(final ThreadContext context) {
        final RubyModule activeRecord = ActiveRecord(context);
        return default_timezone.call(context, activeRecord, activeRecord).asJavaString(); // :utc (or :local)
    }

    // ActiveRecord::Base.default_timezone
    private final CachingCallSite default_timezone = new FunctionalCachingCallSite("default_timezone");

    protected void setIntegerParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyBignum ) { // e.g. HSQLDB / H2 report JDBC type 4
            setBigIntegerParameter(context, connection, statement, index, value, attribute, type);
        }
        else if ( value instanceof RubyNumeric ) {
            statement.setLong(index, RubyNumeric.num2long(value));
        }
        else {
            statement.setLong(index, value.convertToInteger("to_i").asLong(context));
        }
    }

    protected void setBigIntegerParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyBignum bignum) {
            setLongOrDecimalParameter(statement, index, bignum.getValue());
        }
        else if (value instanceof RubyFixnum fixnum) {
            statement.setLong(index, fixnum.getValue());
        }
        else {
            setLongOrDecimalParameter(statement, index, value.convertToInteger("to_i").asBigInteger(context));
        }
    }

    protected static void setLongOrDecimalParameter(final PreparedStatement statement,
        final int index, final BigInteger value) throws SQLException {

        if ( value.bitLength() <= 63 ) {
            statement.setLong(index, value.longValue());
        }
        else {
            statement.setBigDecimal(index, new BigDecimal(value));
        }
    }

    protected void setDoubleParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyNumeric numeric) {
            statement.setDouble(index, numeric.asDouble(context));
        }
        else {
            statement.setDouble(index, value.convertToFloat().asDouble(context));
        }
    }

    protected void setDecimalParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if (value instanceof RubyBigDecimal bigdecimal) {
            statement.setBigDecimal(index, bigdecimal.getValue());
        }
        else if ( value instanceof RubyInteger integer) {
            statement.setBigDecimal(index, new BigDecimal(integer.asBigInteger(context)));
        }
        else if ( value instanceof RubyNumeric numeric) {
            statement.setDouble(index, numeric.asDouble(context));
        }
        else { // e.g. `BigDecimal '42.00000000000000000001'`
            statement.setBigDecimal(index,
                    RubyBigDecimal.newInstance(context, getModule(context, "BigDecimal"), value, asFixnum(context, 0)).getValue());
        }
    }

    protected void setTimestampParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        final RubyTime timeValue = DateTimeUtils.toTime(context, value);
        final DateTime dateTime = dateTimeInDefaultTimeZone(context, timeValue.getDateTime());
        final Timestamp timestamp = new Timestamp(dateTime.getMillis());
        // 1942-11-30T01:02:03.123_456
        if (timeValue.getNSec() > 0) timestamp.setNanos((int) (timestamp.getNanos() + timeValue.getNSec()));

        statement.setTimestamp(index, timestamp, getCalendar(dateTime.getZone()));
    }

    protected static Calendar getCalendar(final DateTimeZone zone) { // final java.util.Date hint
        if (DateTimeZone.UTC == zone) return getCalendarUTC();
        if (DateTimeZone.getDefault() == zone) return new GregorianCalendar();
        return getCalendarInstance( zone.getID() );
    }

    private static Calendar getCalendarInstance(final String ID) {
        return Calendar.getInstance( TimeZone.getTimeZone(ID) );
    }

    private static Calendar getCalendarUTC() {
        return getCalendarInstance("GMT");
    }

    protected void setTimeParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        final RubyTime timeValue = timeInDefaultTimeZone(context, value);
        final DateTime dateTime = timeValue.getDateTime();
        final Time time = new Time(dateTime.getMillis()); // has millis precision

        statement.setTime(index, time, getCalendar(dateTime.getZone()));

        //if ( value instanceof RubyString ) {
        //    statement.setString(index, value.toString()); // assume local time-zone
        //}
        //else { // DateTime ( ActiveSupport::TimeWithZone.to_time )
        //    final RubyFloat timeValue = value.convertToFloat(); // to_f
        //    final Time time = new Time((long) timeValue.getDoubleValue() * 1000);
        //    // java.sql.Time is expected to be only up to (milli) second precision
        //    statement.setTime(index, time, getCalendarUTC());
        //}
    }

    protected void setDateParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( ! "Date".equals(value.getMetaClass().getName(context)) && value.respondsTo("to_date") ) {
            value = value.callMethod(context, "to_date");
        }

        if (value instanceof RubyDate) {
            RubyDate rubyDate = (RubyDate) value;
            statement.setDate(index, rubyDate.toJava(Date.class));
            return;
        }

        // NOTE: assuming Date#to_s does right ...
        statement.setDate(index, Date.valueOf(value.toString()));
    }

    protected void setBooleanParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {
        statement.setBoolean(index, value.isTrue());
    }

    protected void setStringParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        statement.setString(index, value.asString().toString());
    }

    protected void setArrayParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        final String typeName = resolveArrayBaseTypeName(context, attribute);
        final IRubyObject valueForDB = value.callMethod(context, "values");
        Array array = connection.createArrayOf(typeName, ((RubyArray) valueForDB).toArray());
        statement.setArray(index, array);
    }

    protected String resolveArrayBaseTypeName(final ThreadContext context, final IRubyObject attribute) throws SQLException {

        // This shouldn't return nil at this point because we know we have an array typed attribute
        final RubySymbol type = (RubySymbol) attributeSQLType(context, attribute);

        // For some reason the driver doesn't like "character varying" as a type
        if ( type.eql(context.runtime.newSymbol("string")) ) return "varchar";

        final RubyHash nativeTypes = (RubyHash) adapter.callMethod(context, "native_database_types");
        // e.g. `integer: { name: 'integer' }`
        final RubyHash typeInfo = (RubyHash) nativeTypes.op_aref(context, type);

        return typeInfo.op_aref(context, context.runtime.newSymbol("name")).toString();
    }

    protected void setXmlParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        SQLXML xml = connection.createSQLXML();
        xml.setString(value.asString().toString());
        statement.setSQLXML(index, xml);
    }

    protected void setBlobParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyIO ) { // IO/File
            // JDBC 4.0: statement.setBlob(index, ((RubyIO) value).getInStream());
            statement.setBinaryStream(index, ((RubyIO) value).getInStream());
        }
        else { // should be a RubyString
            final ByteList blob = value.asString().getByteList();
            statement.setBytes(index, blob.bytes());

            // JDBC 4.0 :
            //statement.setBlob(index,
            //    new ByteArrayInputStream(bytes.unsafeBytes(), bytes.getBegin(), bytes.getRealSize())
            //);
        }
    }

    protected void setClobParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {
        if ( value instanceof RubyIO ) { // IO/File
            statement.setClob(index, new InputStreamReader(((RubyIO) value).getInStream()));
        }
        else { // should be a RubyString
            final String clob = value.asString().decodeString();
            statement.setCharacterStream(index, new StringReader(clob), clob.length());
            // JDBC 4.0 :
            //statement.setClob(index, new StringReader(clob));
        }
    }

    protected void setObjectParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        statement.setObject(index, value.toJava(Object.class));
    }

    /**
     * Returns a connection (might cause a reconnect if there's none).
     * @param required set to true if a connection is required to exists (e.g. on commit)
     * @return connection
     * @throws <code>ActiveRecord::ConnectionNotEstablished</code> if disconnected
     * @throws <code>ActiveRecord::JDBCError</code> if not connected and connecting fails with a SQL exception
     */
    protected Connection getConnection(final boolean required) throws RaiseException {
        try {
            return getConnectionInternal(required);
        }
        catch (SQLException e) {
            throw wrapException(getRuntime().getCurrentContext(), e);
        }
    }

    protected Connection getConnectionInternal(final boolean required) throws SQLException {
        Connection connection = getConnectionImpl();
        if (connection == null && required) {
            if (!connected) handleNotConnected(getRuntime().getCurrentContext()); // raise ConnectionNotEstablished
            synchronized (this) {
                connection = getConnectionImpl();
                if ( connection == null ) {
                    connectImpl(true); // throws SQLException
                    connection = getConnectionImpl();
                }
            }
        }
        return connection;
    }

    private void handleNotConnected(ThreadContext context) {
        final RubyClass errorClass = getConnectionNotEstablished(context);
        throw context.runtime.newRaiseException(errorClass, "no connection available");
    }

    /**
     * @note might return null if connection is lazy
     * @return current JDBC connection
     */
    protected final Connection getConnectionImpl() {
        return (Connection) dataGetStruct(); // synchronized
    }

    private void setConnection(final Connection connection) {
        close( getConnectionImpl() ); // close previously open connection if there is one
        dataWrapStruct(connection);
        if ( connection != null ) logDriverUsed(connection);
    }

    protected boolean isConnectionValid(final ThreadContext context, final Connection connection) {
        if ( connection == null ) return false;
        Statement statement = null;
        try {
            final String aliveSQL = getAliveSQL(context);
            final int aliveTimeout = getAliveTimeout(context);
            if ( aliveSQL != null ) { // expect a SELECT/CALL SQL statement
                statement = createStatement(context, connection);
                statement.setQueryTimeout(aliveTimeout); // 0 - no timeout
                statement.execute(aliveSQL);
                return true; // connection alive
            }
            return connection.isValid(aliveTimeout); // isValid(0) (default) means no timeout applied
        }
        catch (Exception e) {
            debugMessage(context.runtime, "connection considered not valid due: ", e);
            return false;
        }
        catch (AbstractMethodError e) { // non-JDBC 4.0 driver
            warn( context,
                "driver does not support checking if connection isValid()" +
                " please make sure you're using a JDBC 4.0 compilant driver or" +
                " set `connection_alive_sql: ...` in your database configuration" );
            debugStackTrace(context, e);
            throw e;
        }
        finally { close(statement); }
    }

    private static final String NIL_ALIVE_SQL = new String(); // no set value marker

    private transient String aliveSQL = null;

    private String getAliveSQL(final ThreadContext context) {
        if ( aliveSQL == null ) {
            final IRubyObject alive_sql = getConfigValue(context, "connection_alive_sql");
            aliveSQL = ( alive_sql == context.nil ) ? NIL_ALIVE_SQL : alive_sql.asString().toString();
        }
        return aliveSQL == NIL_ALIVE_SQL ? null : aliveSQL;
    }

    private transient int aliveTimeout = Integer.MIN_VALUE;

    /**
     * internal API do not depend on it
     */
    protected int getAliveTimeout(final ThreadContext context) {
        if ( aliveTimeout == Integer.MIN_VALUE ) {
            final IRubyObject timeout = getConfigValue(context, "connection_alive_timeout");
            aliveTimeout = timeout == context.nil ? 0 : toInt(context, timeout);
        }
        return aliveTimeout;
    }

    private boolean tableExists(final ThreadContext context,
        final Connection connection, final TableName tableName) throws SQLException {
        final IRubyObject matchedTables =
            matchTables(context, connection, tableName.catalog, tableName.schema, tableName.name, getTableTypes(), true);
        // NOTE: allow implementers to ignore checkExistsOnly paramater - empty array means does not exists
        return matchedTables != null && ! matchedTables.isNil() &&
            ( ! (matchedTables instanceof RubyArray) || ! ((RubyArray) matchedTables).isEmpty() );
    }

    @Override
    @JRubyMethod
    @SuppressWarnings("unchecked")
    public IRubyObject inspect(ThreadContext context) {
        final ArrayList<Variable<String>> varList = new ArrayList<>(2);
        final Connection connection = getConnectionImpl();
        varList.add(new VariableEntry<>( "connection", connection == null ? "null" : connection.toString() ));
        //varList.add(new VariableEntry<>( "connectionFactory", connectionFactory == null ? "null" : connectionFactory.toString() ));
        return ObjectSupport.inspect(this, (List) varList);
    }

    /**
     * Match table names for given table name (pattern).
     * @param context
     * @param connection
     * @param catalog
     * @param schemaPattern
     * @param tablePattern
     * @param types table types
     * @param checkExistsOnly an optimization flag (that might be ignored by sub-classes)
     * whether the result really matters if true no need to map table names and a truth-y
     * value is sufficient (except for an empty array which is considered that the table
     * did not exists).
     * @return matched (and Ruby mapped) table names
     * @see #mapTables(ThreadContext, Connection, String, String, String, ResultSet)
     * @throws SQLException
     */
    protected IRubyObject matchTables(final ThreadContext context,
            final Connection connection,
            final String catalog, final String schemaPattern,
            final String tablePattern, final String[] types,
            final boolean checkExistsOnly) throws SQLException {

        final String _tablePattern = caseConvertIdentifierForJdbc(connection, tablePattern);
        final String _schemaPattern = caseConvertIdentifierForJdbc(connection, schemaPattern);
        final DatabaseMetaData metaData = connection.getMetaData();

        ResultSet tablesSet = null;
        try {
            tablesSet = metaData.getTables(catalog, _schemaPattern, _tablePattern, types);
            if ( checkExistsOnly ) { // only check if given table exists
                return tablesSet.next() ? context.tru : null;
            }
            else {
                return mapTables(context, connection, catalog, _schemaPattern, _tablePattern, tablesSet);
            }
        }
        finally { close(tablesSet); }
    }

    // NOTE java.sql.DatabaseMetaData.getTables :
    protected final static int TABLES_TABLE_CAT = 1;
    protected final static int TABLES_TABLE_SCHEM = 2;
    protected final static int TABLES_TABLE_NAME = 3;
    protected final static int TABLES_TABLE_TYPE = 4;

    protected RubyArray mapTables(final ThreadContext context, final Connection connection,
            final String catalog, final String schemaPattern, final String tablePattern,
            final ResultSet tablesSet) throws SQLException {
        final RubyArray tables = newArray(context);
        while ( tablesSet.next() ) {
            String name = tablesSet.getString(TABLES_TABLE_NAME);
            tables.append(context, cachedString(context, caseConvertIdentifierForRails(connection, name)));
        }
        return tables;
    }

    protected static final int TABLE_NAME = 3;
    protected static final int COLUMN_NAME = 4;
    protected static final int DATA_TYPE = 5;
    protected static final int TYPE_NAME = 6;
    protected static final int COLUMN_SIZE = 7;
    protected static final int DECIMAL_DIGITS = 9;
    protected static final int COLUMN_DEF = 13;
    protected static final int IS_NULLABLE = 18;

    /**
     * Create a string which represents a SQL type usable by Rails from the
     * resultSet column meta-data
     * @param resultSet
     */
    protected String typeFromResultSet(final ResultSet resultSet) throws SQLException {
        final int precision = intFromResultSet(resultSet, COLUMN_SIZE);
        final int scale = intFromResultSet(resultSet, DECIMAL_DIGITS);

        final String type = resultSet.getString(TYPE_NAME);
        return formatTypeWithPrecisionAndScale(type, precision, scale);
    }

    protected static int intFromResultSet(
        final ResultSet resultSet, final int column) throws SQLException {
        final int precision = resultSet.getInt(column);
        return ( precision == 0 && resultSet.wasNull() ) ? -1 : precision;
    }

    protected static String formatTypeWithPrecisionAndScale(
        final String type, final int precision, final int scale) {

        if ( precision <= 0 ) return type;

        final StringBuilder typeStr = new StringBuilder().append(type);
        typeStr.append('(').append(precision); // type += "(" + precision;
        if ( scale > 0 ) typeStr.append(',').append(scale); // type += "," + scale;
        return typeStr.append(')').toString(); // type += ")";
    }

    private static IRubyObject defaultValueFromResultSet(final Ruby runtime, final ResultSet resultSet)
        throws SQLException {
        final String defaultValue = resultSet.getString(COLUMN_DEF);
        return defaultValue == null ? runtime.getNil() : RubyString.newInternalFromJavaExternal(runtime, defaultValue);
    }

    protected RubyArray mapColumnsResult(final ThreadContext context,
        final DatabaseMetaData metaData, final TableName components, final ResultSet results)
        throws SQLException {

        return mapColumnsResult(context, metaData, components, results, getJdbcColumnClass(context));
    }

    protected RubyArray mapColumnsResult(final ThreadContext context,
        final DatabaseMetaData metaData, final TableName components, final ResultSet results,
        final RubyClass Column)
        throws SQLException {

        final Ruby runtime = context.runtime;

        final RubyArray columns = newArray(context);
        while ( results.next() ) {
            final String colName = results.getString(COLUMN_NAME);
            final RubyString columnName = cachedString(context, caseConvertIdentifierForRails(metaData, colName));
            final IRubyObject defaultValue = defaultValueFromResultSet( runtime, results );
            final RubyString sqlType = cachedString(context, typeFromResultSet(results));
            final RubyBoolean nullable = runtime.newBoolean( ! results.getString(IS_NULLABLE).trim().equals("NO") );

            final String tabName = results.getString(TABLE_NAME);
            final RubyString tableName = cachedString(context, caseConvertIdentifierForRails(metaData, tabName));

            final IRubyObject type_metadata = adapter.callMethod(context, "fetch_type_metadata", sqlType);

            // (name, default, sql_type_metadata = nil, null = true, table_name = nil, default_function = nil, collation = nil, comment: nil)
            final IRubyObject[] args = new IRubyObject[] {
                columnName, defaultValue, type_metadata, nullable, tableName
            };
            columns.append(context, Column.newInstance(context, args, Block.NULL_BLOCK));
        }
        return columns;
    }

    private static Collection<String> getPrimaryKeyNames(final DatabaseMetaData metaData,
        final TableName components) throws SQLException {
        ResultSet primaryKeys = null;
        try {
            primaryKeys = metaData.getPrimaryKeys(components.catalog, components.schema, components.name);
            final List<String> primaryKeyNames = new ArrayList<>(4);
            while ( primaryKeys.next() ) {
                primaryKeyNames.add( primaryKeys.getString(COLUMN_NAME) );
            }
            return primaryKeyNames;
        }
        finally {
            close(primaryKeys);
        }
    }

    protected IRubyObject mapGeneratedKeys(final ThreadContext context,
            final Connection connection, final Statement statement) throws SQLException {
        if (supportsGeneratedKeys(connection)) {
            return mapQueryResult(context, connection, statement.getGeneratedKeys());
        }
        return context.nil; // Adapters should know they don't support it and override this or Adapter#last_inserted_id
    }

    protected IRubyObject mapGeneratedKeys(
        ThreadContext context, final Connection connection,
        final Statement statement, final Boolean singleResult)
        throws SQLException {
        if ( supportsGeneratedKeys(connection) ) {
            ResultSet genKeys = null;
            try {
                genKeys = statement.getGeneratedKeys();
                // drivers might report a non-result statement without keys
                // e.g. on derby with SQL: 'SET ISOLATION = SERIALIZABLE'
                if ( genKeys == null ) return context.nil;
                return doMapGeneratedKeys(context, genKeys, singleResult);
            }
            catch (SQLFeatureNotSupportedException e) {
                return null; // statement.getGeneratedKeys()
            }
            finally { close(genKeys); }
        }
        return null; // not supported
    }

    protected final IRubyObject doMapGeneratedKeys(ThreadContext context,
        final ResultSet genKeys, final Boolean singleResult)
        throws SQLException {

        IRubyObject firstKey = null;
        // no generated keys - e.g. INSERT statement for a table that does
        // not have and auto-generated ID column :
        boolean next = genKeys.next() && genKeys.getMetaData().getColumnCount() > 0;
        // singleResult == null - guess if only single key returned
        if ( singleResult == null || singleResult) {
            if ( next ) {
                firstKey = mapGeneratedKey(context, genKeys);
                if ( singleResult != null || ! genKeys.next() ) {
                    return firstKey;
                }
                next = true; // 2nd genKeys.next() returned true
            }
            else {
                /* if ( singleResult != null ) */ return context.nil;
            }
        }

        final RubyArray keys = newArray(context);
        if (firstKey != null) keys.append(context, firstKey); // singleResult == null
        while ( next ) {
            keys.append(context, mapGeneratedKey(context, genKeys));
            next = genKeys.next();
        }
        return keys;
    }

    protected IRubyObject mapGeneratedKey(ThreadContext context, final ResultSet genKeys) throws SQLException {
        return asFixnum(context, genKeys.getLong(1));
    }

    private transient Boolean supportsGeneratedKeys;

    protected boolean supportsGeneratedKeys(final Connection connection) throws SQLException {
        Boolean supportsGeneratedKeys = this.supportsGeneratedKeys;
        if (supportsGeneratedKeys == null) {
            supportsGeneratedKeys = this.supportsGeneratedKeys = (Boolean) connection.getMetaData().supportsGetGeneratedKeys();
        }
        return supportsGeneratedKeys;
    }

    /**
     * Converts a JDBC result set into an array (rows) of hashes (row).
     *
     * @param downCase should column names only be in lower case?
     */
    @SuppressWarnings("unchecked")
    private IRubyObject mapToRawResult(final ThreadContext context,
            final Connection connection, final ResultSet resultSet,
            final boolean downCase) throws SQLException {

        final ColumnData[] columns = extractColumns(context, connection, resultSet, downCase);

        final RubyArray results = newArray(context);
        // [ { 'col1': 1, 'col2': 2 }, { 'col1': 3, 'col2': 4 } ]

        while ( resultSet.next() ) {
            results.append(context, mapRawRow(context, context.runtime, columns, resultSet, this));
        }
        return results;
    }

    private IRubyObject yieldResultRows(final ThreadContext context,
            final Connection connection, final ResultSet resultSet,
            final Block block) throws SQLException {

        final ColumnData[] columns = extractColumns(context, connection, resultSet, false);

        final Ruby runtime = context.runtime;
        while ( resultSet.next() ) {
            final IRubyObject[] blockArgs = new IRubyObject[columns.length];
            for ( int i = 0; i < columns.length; i++ ) {
                final ColumnData column = columns[i];
                blockArgs[i] = jdbcToRuby(context, runtime, column.index, column.type, resultSet);
            }
            block.call( context, blockArgs );
        }

        return context.nil; // yielded result rows
    }

    /**
     * Extract columns from result set.
     * @param context
     * @param connection
     * @param resultSet
     * @param downCase
     * @return columns data
     * @throws SQLException
     */
    protected ColumnData[] extractColumns(final ThreadContext context,
        final Connection connection, final ResultSet resultSet,
        final boolean downCase) throws SQLException {
        return setupColumns(context, connection, resultSet.getMetaData(), downCase);
    }

    protected <T> T withConnection(final ThreadContext context, final Callable<T> block)
            throws RaiseException {
        try {
            return withConnection(context, true, block);
        }
        catch (final SQLException e) {
            return handleException(context, e); // should never happen
        }
    }

    private <T> T withConnection(final ThreadContext context, final boolean handleException,
                                 final Callable<T> block) throws RaiseException, SQLException {

        Exception exception; int retry = 0; int i = 0;

        boolean reconnectOnRetry = true; boolean gotConnection = false;
        do {
            boolean autoCommit = true; // retry in-case getAutoCommit throws
            try {
                if ( retry > 0 ) { // we're retrying running the block
                    if ( reconnectOnRetry ) {
                        gotConnection = false;
                        debugMessage(context.runtime, "trying to re-connect using a new connection ...");
                        connectImpl(true); // force a new connection to be created
                    }
                    else {
                        debugMessage(context.runtime, "re-trying transient failure on same connection ...");
                    }
                }

                final Connection connection = getConnectionInternal(false); // getConnection()
                if ( connection == null ) {
                    if ( ! connected ) handleNotConnected(context); // raise ConnectionNotEstablished
                    throw new NoConnectionException();
                }
                gotConnection = true;
                autoCommit = connection.getAutoCommit();
                return block.call(connection);
            }
            catch (final Exception e) { // SQLException or RuntimeException
                exception = e;

                if ( i == 0 ) retry = 1;

                if ( ! gotConnection ) { // SQLException from driver/data-source
                    reconnectOnRetry = connected;
                }
                else if (!autoCommit) {
                    // never retry inside a transaction
                    break;
                }
                else if ( isTransient(exception) ) {
                    reconnectOnRetry = false; // continue;
                }
                else {
                    if ( isConnectionValid(context, getConnectionImpl()) ) {
                        break; // connection not broken yet failed (do not retry)
                    }

                    if ( ! isRecoverable(exception) ) break;

                    reconnectOnRetry = true; // retry calling block again
                }
            }
        } while ( i++ < retry ); // i == 0, retry == 1 means we should retry once

        // (retry) loop ended and we did not return ... exception != null
        return withConnectionError(context, exception, handleException, gotConnection);
    }

    // NOTE: this is meant to be internal - seeing this from the outside is a sign smt is not right!
    private static class NoConnectionException extends RuntimeException {

        @Override
        public Throwable fillInStackTrace() { return this; }

    }

    private <T> T withConnectionError(final ThreadContext context, final Exception exception,
        final boolean handleException, final boolean gotConnection) throws SQLException {
        if ( handleException ) {
            if ( exception instanceof RaiseException ) {
                throw (RaiseException) exception;
            }
            if ( exception instanceof SQLException ) {
                if ( ! gotConnection && exception.getCause() != null ) {
                    return handleException(context, exception.getCause()); // throws
                }
                return handleException(context, exception); // throws
            }
            return handleException(context, getCause(exception)); // throws
        }
        else {
            if ( exception instanceof SQLException ) {
                throw (SQLException) exception;
            }
            if ( exception instanceof RuntimeException ) {
                throw (RuntimeException) exception;
            }
            // won't happen - our try block only throws SQL or Runtime exceptions
            throw new RuntimeException(exception);
        }
    }

    protected boolean isTransient(final Exception exception) {
        return exception instanceof SQLTransientException;
    }

    protected boolean isRecoverable(final Exception exception) {
        return exception instanceof SQLRecoverableException;
        // exception instanceof SQLException; // pre JDBC 4.0 drivers?
    }

    private static Throwable getCause(Throwable exception) {
        Throwable cause = exception.getCause();
        while (cause != null && cause != exception) {
            // SQLException's cause might be DB specific (checked/unchecked) :
            if ( exception instanceof SQLException ) break;
            exception = cause; cause = exception.getCause();
        }
        return exception;
    }

    protected <T> T handleException(final ThreadContext context, Throwable exception) throws RaiseException {
        // NOTE: we shall not wrap unchecked (runtime) exceptions into AR::Error
        // if it's really a misbehavior of the driver throwing a RuntimeExcepion
        // instead of SQLException than this should be overriden for the adapter
        if ( exception instanceof RuntimeException ) {
            throw (RuntimeException) exception;
        }
        debugStackTrace(context, exception);
        throw wrapException(context, exception);
    }

    protected RaiseException wrapException(final ThreadContext context, final Throwable exception) {
        final Ruby runtime = context.runtime;
        if ( exception instanceof SQLException ) {
            return wrapException(context, (SQLException) exception, null);
        }
        if ( exception instanceof RaiseException ) {
            return (RaiseException) exception;
        }
        if ( exception instanceof RuntimeException ) {
            return wrapException(context, context.runtime.getRuntimeError(), exception);
        }
        // NOTE: compat - maybe makes sense or maybe not (e.g. IOException) :
        return wrapException(context, getJDBCError(context), exception);
    }

    public static RaiseException wrapException(final ThreadContext context,
                                               final RubyClass errorClass, final Throwable exception) {
        return wrapException(context, errorClass, exception, exception.toString());
    }

    public static RaiseException wrapException(final ThreadContext context,
                                               final RubyClass errorClass, final Throwable exception, final String message) {
        final RaiseException error = context.runtime.newRaiseException(errorClass, message);
        error.initCause(exception);
        return error;
    }

    protected RaiseException wrapException(final ThreadContext context, final SQLException exception, String message) {
        return wrapSQLException(context, getJDBCError(context), exception, message);
    }

    protected RaiseException wrapException(final ThreadContext context, final RubyClass errorClass, final SQLException exception) {
        return wrapSQLException(context, errorClass, exception, null);
    }

    private static RaiseException wrapSQLException(final ThreadContext context, final RubyClass errorClass,
                                                   final SQLException exception, String message) {
        final Ruby runtime = context.runtime;
        if ( message == null ) {
            message = SQLException.class == exception.getClass() ?
                    exception.getMessage() : exception.toString(); // useful to easily see type on Ruby side
        }
        final RaiseException raise = wrapException(context, errorClass, exception, message);
        final RubyException error = raise.getException(); // assuming JDBCError internals :
        error.setInstanceVariable("@jdbc_exception", JavaEmbedUtils.javaToRuby(runtime, exception));
        return raise;
    }

    protected final RaiseException newNoDatabaseError(final SQLException ex) {
        var context = getRuntime().getCurrentContext();
        return wrapException(context, getNoDatabaseError(context), ex);
    }

    private IRubyObject convertJavaToRuby(final Object object) {
        return JavaUtil.convertJavaToRuby( getRuntime(), object );
    }

    /**
     * Some databases support schemas and others do not.
     * For ones which do this method should return true, aiding in decisions regarding schema vs database determination.
     */
    protected boolean databaseSupportsSchemas() {
        return false;
    }

    private static final byte[] SELECT = new byte[] { 's','e','l','e','c','t' };
    private static final byte[] WITH = new byte[] { 'w','i','t','h' };
    private static final byte[] SHOW = new byte[] { 's','h','o','w' };
    private static final byte[] CALL = new byte[]{ 'c','a','l','l' };

    @JRubyMethod(name = "select?", required = 1, meta = true, frame = false)
    public static RubyBoolean select_p(final ThreadContext context,
        final IRubyObject self, final IRubyObject sql) {
        return context.runtime.newBoolean( isSelect(sql.asString()) );
    }

    private static boolean isSelect(final RubyString sql) {
        final ByteList sqlBytes = sql.getByteList();
        return StringHelper.startsWithIgnoreCase(sqlBytes, SELECT) ||
               StringHelper.startsWithIgnoreCase(sqlBytes, WITH) ||
               StringHelper.startsWithIgnoreCase(sqlBytes, SHOW) ||
               StringHelper.startsWithIgnoreCase(sqlBytes, CALL);
    }

    private static final byte[] INSERT = new byte[] { 'i','n','s','e','r','t' };

    @JRubyMethod(name = "insert?", required = 1, meta = true, frame = false)
    public static RubyBoolean insert_p(final ThreadContext context,
        final IRubyObject self, final IRubyObject sql) {
        final ByteList sqlBytes = sql.asString().getByteList();
        return context.runtime.newBoolean( startsWithIgnoreCase(sqlBytes, INSERT) );
    }

    protected static boolean startsWithIgnoreCase(final ByteList bytes, final byte[] start) {
        return StringHelper.startsWithIgnoreCase(bytes, start);
    }

    // maps a AR::Result row
    protected static IRubyObject mapRow(final ThreadContext context, final Ruby runtime,
        final ColumnData[] columns, final ResultSet resultSet,
        final RubyJdbcConnection connection) throws SQLException {

        final IRubyObject[] row = new IRubyObject[columns.length];

        for (int i = 0; i < columns.length; i++) {
            final ColumnData column = columns[i];
            row[i] = connection.jdbcToRuby(context, runtime, column.index, column.type, resultSet);
        }

        return newArrayNoCopy(context, row);
    }

    private static IRubyObject mapRawRow(final ThreadContext context, final Ruby runtime,
        final ColumnData[] columns, final ResultSet resultSet,
        final RubyJdbcConnection connection) throws SQLException {

        final RubyHash row = new RubyHash(runtime, columns.length);

        for ( int i = 0; i < columns.length; i++ ) {
            final ColumnData column = columns[i];
            // NOTE: we know keys are always String so maybe we could take it even further ?!
            row.fastASetCheckString(runtime, column.getName(context),
                connection.jdbcToRuby(context, runtime, column.index, column.type, resultSet)
            );
        }

        return row;
    }

    protected static IRubyObject newResult(final ThreadContext context, ColumnData[] columns, IRubyObject rows) {
        final RubyClass Result = getResult(context);
        return Result.newInstance(context, columnsToArray(context, columns), rows, Block.NULL_BLOCK); // Result.new
    }

    protected static IRubyObject newEmptyResult(final ThreadContext context) {
        final RubyClass Result = getResult(context);
        return Result.newInstance(context, newEmptyArray(context), newEmptyArray(context), Block.NULL_BLOCK); // Result.new
    }

    private static RubyArray columnsToArray(ThreadContext context, ColumnData[] columns) {
        final IRubyObject[] cols = new IRubyObject[columns.length];

        for ( int i = 0; i < columns.length; i++ ) cols[i] = columns[i].getName(context);

        return newArrayNoCopy(context, cols);
    }

    protected static final class TableName {

        public final String catalog, schema, name;

        public TableName(String catalog, String schema, String table) {
            this.catalog = catalog;
            this.schema = schema;
            this.name = table;
        }

        @Override
        public String toString() {
            return getClass().getName() + "{catalog=" + catalog + ",schema=" + schema + ",name=" + name + "}";
        }

    }

    /**
     * Extract the table name components for the given name e.g. "mycat.sys.entries"
     *
     * @param connection
     * @param catalog (optional) catalog to use if table name does not contain
     *                 the catalog prefix
     * @param schema (optional) schema to use if table name does not have one
     * @param tableName the table name
     * @return (parsed) table name
     *
     * @throws IllegalArgumentException for invalid table name format
     * @throws SQLException
     */
    protected TableName extractTableName(
            final Connection connection, String catalog, String schema,
            final String tableName) throws IllegalArgumentException, SQLException {

        final List<String> nameParts = split(tableName, '.');
        final int len = nameParts.size();
        if ( len > 3 ) {
            throw new IllegalArgumentException("table name: " + tableName + " should not contain more than 2 '.'");
        }

        String name = tableName;

        if ( len == 2 ) {
            schema = nameParts.get(0); name = nameParts.get(1);
        }
        else if ( len == 3 ) {
            catalog = nameParts.get(0);
            schema = nameParts.get(1);
            name = nameParts.get(2);
        }

        if ( schema != null ) {
            schema = caseConvertIdentifierForJdbc(connection, schema);
        }
        name = caseConvertIdentifierForJdbc(connection, name);

        if ( schema != null && ! databaseSupportsSchemas() ) {
            catalog = schema;
        }
        if ( catalog == null ) catalog = connection.getCatalog();

        return new TableName(catalog, schema, name);
    }

    protected static List<String> split(final String str, final char sep) {
        ArrayList<String> split = new ArrayList<>(4);
        int s = 0;
        for ( int i = 0; i < str.length(); i++ ) {
            if ( str.charAt(i) == sep ) {
                split.add( str.substring(s, i) ); s = i + 1;
            }
        }
        if ( s < str.length() ) split.add( str.substring(s) );
        return split;
    }

    protected final TableName extractTableName(
            final Connection connection, final String schema,
            final String tableName) throws IllegalArgumentException, SQLException {
        return extractTableName(connection, null, schema, tableName);
    }

    protected IRubyObject valueForDatabase(final ThreadContext context, final IRubyObject attribute) {
        return attribute.callMethod(context, "value_for_database");
    }

    // FIXME: This should not be static and will be exposed via api in connection as instance method.
    public static final StringCache STRING_CACHE = new StringCache();

    protected static RubyString cachedString(final ThreadContext context, final String str) {
        return STRING_CACHE.get(context, str);
    }

    protected static final class ColumnData {

        @Deprecated
        public RubyString name;
        public final int index;
        public final int type;

        private final String label;

        @Deprecated
        public ColumnData(RubyString name, int type, int idx) {
            this.name = name;
            this.type = type;
            this.index = idx;

            this.label = name.toString();
        }

        public ColumnData(String label, int type, int idx) {
            this.label = label;
            this.type = type;
            this.index = idx;
        }

        // NOTE: meant temporary for others to update from accesing name
        ColumnData(ThreadContext context, String label, int type, int idx) {
            this(label, type, idx);
            name = cachedString(context, label);
        }

        public String getName() {
            return label;
        }

        RubyString getName(final ThreadContext context) {
            if ( name != null ) return name;
            return name = cachedString(context, label);
        }

        @Override
        public String toString() {
            return "'" + label + "'i" + index + "t" + type + "";
        }

    }

    private ColumnData[] setupColumns(
            final ThreadContext context,
            final Connection connection,
            final ResultSetMetaData resultMetaData,
            final boolean downCase) throws SQLException {

        final int columnCount = resultMetaData.getColumnCount();
        final ColumnData[] columns = new ColumnData[columnCount];

        for ( int i = 1; i <= columnCount; i++ ) { // metadata is one-based
            String name = resultMetaData.getColumnLabel(i);
            if ( downCase ) {
                name = name.toLowerCase();
            } else {
                name = caseConvertIdentifierForRails(connection, name);
            }

            final int columnType = resultMetaData.getColumnType(i);
            columns[i - 1] = new ColumnData(context, name, columnType, i);
        }

        return columns;
    }

    // JDBC API Helpers :

    protected static void close(final Connection connection) {
        if ( connection != null ) {
            try { connection.close(); }
            catch (final Exception e) { /* NOOP */ }
        }
    }

    public static void close(final ResultSet resultSet) {
        if (resultSet != null) {
            try { resultSet.close(); }
            catch (final Exception e) { /* NOOP */ }
        }
    }

    public static void close(final Statement statement) {
        if (statement != null) {
            try { statement.close(); }
            catch (final Exception e) { /* NOOP */ }
        }
    }

    // DEBUG-ing helpers :

    private static boolean debug = Boolean.parseBoolean( SafePropertyAccessor.getProperty("arjdbc.debug") );

    public static boolean isDebug() { return debug; }

    public static boolean isDebug(final Ruby runtime) {
        return debug || ( runtime != null && runtime.isDebug() );
    }

    public static void setDebug(boolean debug) {
        RubyJdbcConnection.debug = debug;
    }

    //public static void debugMessage(final ThreadContext context, final String msg) {
    //    if ( debug || ( context != null && context.runtime.isDebug() ) ) {
    //        final PrintStream out = context != null ? context.runtime.getOut() : System.out;
    //        out.println(msg);
    //    }
    //}

    public static void debugMessage(final Ruby runtime, final Object msg) {
        if ( isDebug(runtime) ) {
            final PrintStream out = runtime != null ? runtime.getOut() : System.out;
            out.print("ArJdbc: "); out.println(msg);
        }
    }

    public static void debugMessage(final ThreadContext context, final IRubyObject obj) {
        if ( isDebug(context.runtime) ) {
            debugMessage(context.runtime, obj.callMethod(context, "inspect"));
        }
    }

    public static void debugMessage(final Ruby runtime, final String msg, final Object e) {
        if ( isDebug(runtime) ) {
            final PrintStream out = runtime != null ? runtime.getOut() : System.out;
            out.print("ArJdbc: "); out.print(msg); out.println(e);
        }
    }

    protected static void debugErrorSQL(final ThreadContext context, final String sql) {
        if ( debug || ( context != null && context.runtime.isDebug() ) ) {
            final PrintStream out = context != null ? context.runtime.getOut() : System.out;
            out.print("ArJdbc: (error) SQL = "); out.println(sql);
        }
    }

    // disables full (Java) traces to be printed while DEBUG is on
    private static final Boolean debugStackTrace;
    static {
        String debugTrace = SafePropertyAccessor.getProperty("arjdbc.debug.trace");
        debugStackTrace = debugTrace == null ? null : Boolean.parseBoolean(debugTrace);
    }

    public static void debugStackTrace(final ThreadContext context, final Throwable e) {
        if ( debug || ( context != null && context.runtime.isDebug() ) ) {
            final PrintStream out = context != null ? context.runtime.getOut() : System.out;
            if ( debugStackTrace == null || debugStackTrace) {
                e.printStackTrace(out);
            }
            else {
                out.println(e);
            }
        }
    }

    protected void warn(final ThreadContext context, final String message) {
        arjdbc.ArJdbcModule.warn(context, message);
    }

    private static boolean driverUsedLogged;

    private void logDriverUsed(final Connection connection) {
        if (debug && !driverUsedLogged) {
            driverUsedLogged = true;
            try {
                final DatabaseMetaData meta = connection.getMetaData();
                debugMessage(getRuntime(), "using driver " + meta.getDriverVersion());
            }
            catch (Exception e) {
                debugMessage(getRuntime(), "failed to log driver ", e);
            }
        }
    }

}
