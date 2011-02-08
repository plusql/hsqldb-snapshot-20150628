/* Copyright (c) 2001-2010, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb;

import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.lib.IntKeyHashMap;
import org.hsqldb.lib.LongKeyHashMap;
import org.hsqldb.lib.LongValueHashMap;
import org.hsqldb.result.Result;

/**
 * This class manages the reuse of Statement objects for prepared
 * statements for a Session instance.<p>
 *
 * A compiled statement is registered by a session to be managed. Once
 * registered, it is linked with one or more sessions.<p>
 *
 * The sql statement text distinguishes different compiled statements and acts
 * as lookup key when a session initially looks for an existing instance of
 * the compiled sql statement.<p>
 *
 * Once a session is linked with a statement, it uses the uniqe compiled
 * statement id for the sql statement to access the statement.<p>
 *
 * Changes to database structure via DDL statements, will result in all
 * registered Statement objects to become invalidated. This is done by
 * comparing the schema change and compile timestamps. When a session
 * subsequently attempts to use an invalidated Statement via its id, it will
 * reinstantiate the Statement using its sql statement still held by this class.<p>
 *
 * This class keeps count of the number of time each registered compiled
 * statement is linked to a session. It unregisters a compiled statement when
 * no session remains linked to it.<p>
 *
 * Modified by fredt@users from the original by boucherb@users to simplify,
 * support multiple identical prepared statements per session, and avoid
 * memory leaks. Modified further to support schemas. Changed implementation
 * in 1.9 as a session object<p>
 *
 * @author Campbell Boucher-Burnet (boucherb@users dot sourceforge.net)
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 *
 * @version 2.0.1
 * @since 1.7.2
 */
public final class StatementManager {

    /**
     * The Database for which this object is managing
     * CompiledStatement objects.
     */
    private Database database;

    /** Map: Schema id (int) => {Map: SQL String => Compiled Statement id (long)} */
    private IntKeyHashMap schemaMap;

    /** Map: Compiled Statement id (int) => SQL String */
    private LongKeyHashMap sqlLookup;

    /** Map: Compiled statment id (int) => CompiledStatement object. */
    private LongKeyHashMap csidMap;

    /**
     * Monotonically increasing counter used to assign unique ids to compiled
     * statements.
     */
    private long next_cs_id;

    /**
     * Constructs a new instance of <code>CompiledStatementManager</code>.
     *
     * @param database the Database instance for which this object is to
     *      manage compiled statement objects.
     */
    StatementManager(Database database) {

        this.database = database;
        schemaMap     = new IntKeyHashMap();
        sqlLookup     = new LongKeyHashMap();
        csidMap       = new LongKeyHashMap();
        next_cs_id    = 0;
    }

    /**
     * Clears all internal data structures, removing any references to compiled statements.
     */
    synchronized void reset() {

        schemaMap.clear();
        sqlLookup.clear();
        csidMap.clear();

        next_cs_id = 0;
    }

    /**
     * Retrieves the next compiled statement identifier in the sequence.
     *
     * @return the next compiled statement identifier in the sequence.
     */
    private long nextID() {

        next_cs_id++;

        return next_cs_id;
    }

    /**
     * Retrieves the registered compiled statement identifier associated with
     * the specified SQL String, or a value less than zero, if no such
     * statement has been registered.
     *
     * @param schema the schema id
     * @param sql the SQL String
     * @return the compiled statement identifier associated with the
     *      specified SQL String
     */
    private long getStatementID(HsqlName schema, String sql) {

        LongValueHashMap sqlMap =
            (LongValueHashMap) schemaMap.get(schema.hashCode());

        if (sqlMap == null) {
            return -1;
        }

        return sqlMap.get(sql, -1);
    }

    /**
     * Returns an existing CompiledStatement object with the given
     * statement identifier. Returns null if the CompiledStatement object
     * has been invalidated and cannot be recompiled
     *
     * @param session the session
     * @param csid the identifier of the requested CompiledStatement object
     * @return the requested CompiledStatement object
     */
    public synchronized Statement getStatement(Session session, long csid) {

        Statement cs = (Statement) csidMap.get(csid);

        if (cs == null) {
            return null;
        }

        if (cs.getCompileTimestamp()
                < database.schemaManager.getSchemaChangeTimestamp()) {
            String   sql       = (String) sqlLookup.get(csid);
            HsqlName oldSchema = session.getCurrentSchemaHsqlName();

            // revalidate with the original schema
            try {
                HsqlName schema = cs.getSchemaName();

                // checks the old schema exists
                session.setSchema(schema.name);

                StatementDML si = null;

                if (cs.generatedResultMetaData() != null) {
                    si = (StatementDML) cs;
                }

                cs = session.compileStatement(sql, cs.getResultProperties());

                cs.setID(csid);
                cs.setCompileTimestamp(
                    database.txManager.getGlobalChangeTimestamp());

                if (si != null) {
                    cs.setGeneratedColumnInfo(si.generatedType,
                                              si.generatedInputMetaData);
                }

                csidMap.put(csid, cs);
            } catch (Throwable t) {
                freeStatement(csid);

                return null;
            } finally {
                session.setCurrentSchemaHsqlName(oldSchema);
            }
        }

        return cs;
    }

    /**
     * Registers a compiled statement to be managed.
     *
     * The only caller should be a Session that is attempting to prepare
     * a statement for the first time or process a statement that has been
     * invalidated due to DDL changes.
     *
     * @param csid existing id or negative if the statement is not yet managed
     * @param cs The CompiledStatement to add
     * @return The compiled statement id assigned to the CompiledStatement
     *  object
     */
    private long registerStatement(long csid, Statement cs) {

        if (csid < 0) {
            csid = nextID();

            int schemaid = cs.getSchemaName().hashCode();
            LongValueHashMap sqlMap =
                (LongValueHashMap) schemaMap.get(schemaid);

            if (sqlMap == null) {
                sqlMap = new LongValueHashMap();

                schemaMap.put(schemaid, sqlMap);
            }

            sqlMap.put(cs.getSQL(), csid);
            sqlLookup.put(csid, cs.getSQL());
        }

        cs.setID(csid);
        cs.setCompileTimestamp(database.txManager.getGlobalChangeTimestamp());
        csidMap.put(csid, cs);

        return csid;
    }

    /**
     * Removes one (or all) of the links between a session and a compiled
     * statement. If the statement is not linked with any other session, it is
     * removed from management.
     *
     * @param csid the compiled statment identifier
     * @param sessionID the session identifier
     * @param freeAll if true, remove all links to the session
     */
    synchronized void freeStatement(long csid) {

        if (csid == -1) {

            // statement was never added
            return;
        }

        Statement cs = (Statement) csidMap.remove(csid);

        if (cs != null) {
            int schemaid = cs.getSchemaName().hashCode();
            LongValueHashMap sqlMap =
                (LongValueHashMap) schemaMap.get(schemaid);
            String sql = (String) sqlLookup.remove(csid);

            sqlMap.remove(sql);
        }
    }

    /**
     * Compiles an SQL statement and returns a CompiledStatement Object
     *
     * @param session the session
     * @throws Throwable
     * @return CompiledStatement
     */
    synchronized Statement compile(Session session,
                                   Result cmd) throws Throwable {

        String    sql  = cmd.getMainString();
        long      csid = getStatementID(session.currentSchema, sql);
        Statement cs   = (Statement) csidMap.get(csid);
        int       props;

        if (cs == null || !cs.isValid()
                || cs.getCompileTimestamp()
                   < database.schemaManager.getSchemaChangeTimestamp()) {
            props = cmd.getExecuteProperties();
            cs    = session.compileStatement(sql, props);
            csid  = registerStatement(csid, cs);
        }

        cs.setGeneratedColumnInfo(cmd.getGeneratedResultType(),
                                  cmd.getGeneratedResultMetaData());

        return cs;
    }
}
