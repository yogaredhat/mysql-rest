/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.andy.rest.resources;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;

import org.apache.log4j.Logger;

import com.andy.rest.beans.Input;
import com.andy.rest.beans.Response;
import com.andy.rest.beans.TreeTraversalListener;
import com.andy.rest.exception.DependencyException;
import com.andy.rest.exception.EntityException;
import com.andy.rest.util.Utils;

import io.swagger.annotations.Api;

@Api("MySql DML Operations")
@Path("mdml")
@Produces("application/json")
public class MysqlDML {

    private static final Logger LOGGER = Logger.getLogger(MysqlDML.class);

    static {
	try {
	    Class.forName(Utils.BUNDLE.getProperty("mysql.db.driver"));
	} catch (ClassNotFoundException e) {
	    LOGGER.error("Error initializing MySql DB Driver");
	    System.out.println("");
	}
    }

    @Context
    @NotNull
    private ResourceContext resourceContext;

    @Context
    private Request request;

    @Context
    private Application application;

    private Connection connection;

    private Utils utils = new Utils();

    @PostConstruct
    public void init() {
	LOGGER.debug("Initialized " + this.getClass().getName());
    }

    @PreDestroy
    public void destroy() throws SQLException {
	LOGGER.debug("Destroying " + this.getClass().getName());
	if (connection != null && !connection.isClosed())
	    connection.close();
    }

    private Connection getConnection() throws SQLException {
	return getConnection(Utils.BUNDLE.getProperty("mysql.db.dbName"));
    }

    private Connection getConnection(String dbName) throws SQLException {
	// if (connection == null)
	connection = DriverManager.getConnection(Utils.BUNDLE.getProperty("mysql.db.url") + "/" + dbName,
		Utils.BUNDLE.getProperty("mysql.db.user"), Utils.BUNDLE.getProperty("mysql.db.password"));
	return connection;
    }

    private List<String> getColumns(ResultSetMetaData md) throws SQLException {
	List<String> columns = new ArrayList<String>();
	for (int i = 0; i < md.getColumnCount(); i++) {
	    columns.add(md.getColumnName(i + 1));
	}
	return columns;
    }

    @POST
    @Path("query")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response fetch(@QueryParam("dbName") String dbName, @QueryParam("query") String query) {

	Response response = new Response();
	long t1 = System.currentTimeMillis();
	try {

	    Connection con = getConnection();
	    Statement st = con.createStatement();

	    ResultSet rs = st.executeQuery(query);

	    ResultSetMetaData md = rs.getMetaData();

	    LOGGER.debug("Column Count: " + md.getColumnCount());
	    LOGGER.debug("Columns: " + getColumns(md));
	    while (rs.next()) {
		Map<String, Object> re = new HashMap<String, Object>(md.getColumnCount());
		for (int i = 0; i < md.getColumnCount(); i++) {
		    Object obj = rs.getObject(i + 1);
		    re.put(md.getColumnName(i + 1), obj);
		}
		response.getObjects().add(re);
	    }

	} catch (Exception e) {
	    response.setStatus(false);
	    response.setMessage("Error: " + e.getMessage());
	} finally {
	    long t2 = System.currentTimeMillis();
	    response.setTime(t2 - t1);
	}
	return response;
    }

    @POST
    @Path("insertOrUpdate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertOrUpdate(@QueryParam("dbName") String dbName, Input<Object> input) {

	Response response = new Response();
	long t1 = System.currentTimeMillis();

	Connection con = null;

	try {

	    con = getConnection(dbName);

	    final Connection connection = con;

	    con.setAutoCommit(false);

	    LOGGER.debug("Transactional: " + input.isTransaction());

	    Set<String> dependencySet = new HashSet<String>();

	    // Foreign key's for each entity and its foreign column mapped to
	    // target entity
	    // e.g. RoomBooking-idRoom -> Room
	    final Map<String, String> foreignKeyMap = new HashMap<String, String>();

	    // List of entities to be inserted in exact order
	    final List<String> list = new ArrayList<String>();

	    final Map<String, Integer> columnDataTypes = new HashMap<String, Integer>();

	    final TreeTraversalListener tr = new TreeTraversalListener() {

		@Override
		public void childernTraversed(String entity, int numberOfChildren) {
		    list.add(entity);
		}

		@Override
		public void childFound(String entity, String foreignEntity, String foreignKeyName) {
		    foreignKeyMap.put(entity + "-" + foreignKeyName, foreignEntity);
		    try {
			utils.addColumnTypes(connection, entity, columnDataTypes);
			LOGGER.debug(entity + "-> ColumnTypes: " + columnDataTypes);
		    } catch (SQLException e) {
			throw new RuntimeException(e);
		    }
		}
	    };

	    for (String entity : input.getEntityGroups().keySet()) {

		if (dependencySet.contains(entity)) {
		    continue;
		}

		dependencySet.add(entity);

		utils.addColumnTypes(connection, entity, columnDataTypes);
		LOGGER.debug(entity + "-> ColumnTypes: " + columnDataTypes);

		LOGGER.debug("Tree for " + entity);

		try {
		    utils.traverse(con, entity, dependencySet, input.getEntityGroups().keySet(), tr);
		} catch (SQLException e) {
		    throw new DependencyException("Error creating dependency list for " + entity + ", Info: " + e.getMessage(), e);
		}
	    }

	    Map<String, Integer> generatedIds = new HashMap<String, Integer>();

	    for (String entity : list) {
		LOGGER.debug("Entity: " + entity.toUpperCase());

		List<Map<String, Object>> objects = input.getEntityGroups().get(entity);

		int objectIndex = 0;
		for (Map<String, Object> object : objects) {
		    LOGGER.debug("Object: " + object);
		    try {
			runStatement(entity, objectIndex, object, con, foreignKeyMap, generatedIds, columnDataTypes);
		    } catch (SQLException e) {
			throw new EntityException(
				"Failed: Insert/Update " + entity + " at index " + objectIndex + ", Info: " + e.getMessage(), e);
		    }
		    objectIndex++;
		}
	    }
	    con.commit();

	    LOGGER.debug("Transaction Completed Successfully");

	} catch (Exception e) {
	    if (con != null) {
		try {
		    con.rollback();
		} catch (SQLException e1) {
		    e1.printStackTrace();
		}
	    }
	    response.setStatus(false);
	    response.setMessage("Error: " + e.getMessage());
	} finally {
	    long t2 = System.currentTimeMillis();
	    response.setTime(t2 - t1);
	}
	return response;
    }

    private void runStatement(String entity, int objectIndex, Map<String, Object> object, Connection con,
	    Map<String, String> foreignKeyMap, Map<String, Integer> generatedIds, Map<String, Integer> columnDataTypes)
	    throws SQLException, ParseException {

	PreparedStatement statement = null;
	ResultSet keys = null;
	try {

	    Set<String> columns = object.keySet();

	    if (columns.contains("id")) {
		// If id is given for an object then it will be considered an
		// Update Statement
		LOGGER.debug("Update -> " + entity + "[" + objectIndex + "]");
		statement = runUpdate(entity, object, con, foreignKeyMap, generatedIds, columns, columnDataTypes);
	    } else {
		LOGGER.debug("Insert -> " + entity + "[" + objectIndex + "]");
		statement = runInsert(entity, object, con, foreignKeyMap, generatedIds, columns, columnDataTypes);
		keys = statement.getGeneratedKeys();
		int id = utils.fetchGeneratedId(keys);
		if (id != -1) {
		    generatedIds.put(entity + "-" + objectIndex, id);
		}
	    }
	} finally {
	    if (keys != null) {
		keys.close();
	    }
	    if (statement != null) {
		statement.close();
	    }
	}
    }

    private PreparedStatement runInsert(String entity, Map<String, Object> object, Connection con,
	    Map<String, String> foreignKeyMap, Map<String, Integer> generatedIds, Set<String> columns,
	    Map<String, Integer> columnDataTypes) throws SQLException, ParseException {

	PreparedStatement statement;
	String sql = utils.createInsertQuery(entity, object, columns);
	statement = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
	utils.updateParameters(entity, object, foreignKeyMap, generatedIds, statement, columns, columnDataTypes);
	statement.executeUpdate();
	return statement;
    }

    private PreparedStatement runUpdate(String entity, Map<String, Object> object, Connection con,
	    Map<String, String> foreignKeyMap, Map<String, Integer> generatedIds, Set<String> columns,
	    Map<String, Integer> columnDataTypes) throws SQLException, ParseException {
	PreparedStatement statement;
	String sql = utils.createUpdateQuery(entity, object, columns);
	statement = con.prepareStatement(sql);
	int parametersUpdated = utils.updateParameters(entity, object, foreignKeyMap, generatedIds, statement, columns,
		columnDataTypes);
	statement.setObject(parametersUpdated, object.get("id"));
	statement.executeUpdate();
	return statement;
    }

}