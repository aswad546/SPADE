/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2016 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import spade.core.AbstractEdge;
import spade.core.AbstractStorage;
import spade.core.AbstractVertex;
import spade.core.Graph;
import spade.core.Settings;
import spade.core.Vertex;
import spade.core.Edge;

import javax.swing.plaf.nimbus.State;

/**
 * Basic SQL storage implementation.
 *
 * @author Dawood Tariq and Hasanat Kazmi
 */
public class SQL extends AbstractStorage {

    private Connection dbConnection;
    private HashSet<String> vertexAnnotations;
    private HashSet<String> edgeAnnotations;
    private final String VERTEX_TABLE = "VERTEX";
    private final String EDGE_TABLE = "EDGE";
    private final boolean ENABLE_SANITAZATION = true;
    private static final String ID_STRING = Settings.getProperty("storage_identifier");
    private static final String DIRECTION_ANCESTORS = Settings.getProperty("direction_ancestors");
    private static final String DIRECTION_DESCENDANTS = Settings.getProperty("direction_descendants");
    public static boolean TEST_ENV = false;
    public static Graph TEST_GRAPH ;

    // private Statement batch_statement;
    @Override
    public boolean initialize(String arguments) {
        vertexAnnotations = new HashSet<>();
        edgeAnnotations = new HashSet<>();

        // Arguments consist of 4 space-separated tokens: 'driver URL username password'
        try {
            String[] tokens = arguments.split("\\s+");
            String driver = tokens[0].equalsIgnoreCase("default") ? "org.h2.Driver" : tokens[0];
            // for postgres, it is jdbc:postgres://localhost/5432/database_name
            String databaseURL = tokens[1].equalsIgnoreCase("default") ? "jdbc:h2:/tmp/spade.sql" : tokens[1];
            String username = tokens[2].equalsIgnoreCase("null") ? "" : tokens[2];
            String password = tokens[3].equalsIgnoreCase("null") ? "" : tokens[3];

            Class.forName(driver).newInstance();
            dbConnection = DriverManager.getConnection(databaseURL, username, password);
            dbConnection.setAutoCommit(false);

            Statement dbStatement = dbConnection.createStatement();
            String key_syntax = driver.equalsIgnoreCase("org.postgresql.Driver")? " SERIAL PRIMARY KEY, " : " INT PRIMARY KEY AUTO_INCREMENT, ";
            // Create vertex table if it does not already exist
            String createVertexTable = "CREATE TABLE IF NOT EXISTS "
                    + VERTEX_TABLE
                    + "(vertexId" + key_syntax
                    + "type VARCHAR(32) NOT NULL, "
                    + "hash INT NOT NULL"
                    + ")";
            dbStatement.execute(createVertexTable);
            String createEdgeTable = "CREATE TABLE IF NOT EXISTS "
                    + EDGE_TABLE
                    + " (vertexId" + key_syntax
                    + "type VARCHAR(32) NOT NULL ,"
                    + "hash INT NOT NULL, "
                    + "srcVertexHash INT NOT NULL, "
                    + "dstVertexHash INT NOT NULL"
                    + ")";
            dbStatement.execute(createEdgeTable);
            dbStatement.close();


            return true;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | SQLException ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }


    @Override
    public boolean shutdown() {
        try {
            dbConnection.commit();
            dbConnection.close();
            return true;
        } catch (Exception ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }

    private String sanitizeColumn(String column) {
        if (ENABLE_SANITAZATION) {
            column = column.replaceAll("[^a-zA-Z0-9]+", "");
        }
        return column;
    }

    private boolean addColumn(String table, String column) {
        // If this column has already been added before for this table, then return
        if ((table.equalsIgnoreCase(VERTEX_TABLE)) && vertexAnnotations.contains(column)) {
            return true;
        } else if ((table.equalsIgnoreCase(EDGE_TABLE)) && edgeAnnotations.contains(column)) {
            return true;
        }

        try {
            Statement columnStatement = dbConnection.createStatement();
            String statement = "ALTER TABLE `" + table 
                        + "` ADD COLUMN `" 
                        + column 
                        + "` VARCHAR(256);";
            columnStatement.execute(statement);
            dbConnection.commit();
            columnStatement.close();

            if (table.equalsIgnoreCase(VERTEX_TABLE)) {
                vertexAnnotations.add(column);
            } else if (table.equalsIgnoreCase(EDGE_TABLE)) {
                edgeAnnotations.add(column);
            }

            return true;
        } catch (SQLException ex) {
            // column duplicate already present error codes 
            // MySQL = 1060 
            // H2 = 42121
            if (ex.getErrorCode() == 1060 || ex.getErrorCode() == 42121) { 
                if (table.equalsIgnoreCase(VERTEX_TABLE)) {
                    vertexAnnotations.add(column);
                } else if (table.equalsIgnoreCase(EDGE_TABLE)) {
                    edgeAnnotations.add(column);
                }     
                return true;
            }
        } catch (Exception ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
        return false;
    }

    @Override
    public boolean putVertex(AbstractVertex incomingVertex) {
        // Use StringBuilder to build the SQL insert statement
        StringBuilder insertStringBuilder = new StringBuilder("INSERT INTO " + VERTEX_TABLE + " (type, hash, ");
        for (String annotationKey : incomingVertex.getAnnotations().keySet()) {
            if (annotationKey.equalsIgnoreCase("type")) {
                continue;
            }

            // Sanitize column name to remove special characters
            String newAnnotationKey = sanitizeColumn(annotationKey);

            // As the annotation keys are being iterated, add them as new
            // columns to the table if they do not already exist
            addColumn(VERTEX_TABLE, newAnnotationKey);

            insertStringBuilder.append("");
            insertStringBuilder.append(newAnnotationKey);
            insertStringBuilder.append(", ");
        }

        // Eliminate the last 2 characters from the string (", ") and begin adding values
        String insertString = insertStringBuilder.substring(0, insertStringBuilder.length() - 2);
        insertStringBuilder = new StringBuilder(insertString + ") VALUES (");

        // Add the type and hash code
        insertStringBuilder.append("'");
        insertStringBuilder.append(incomingVertex.type());
        insertStringBuilder.append("', ");
        insertStringBuilder.append(incomingVertex.hashCode());
        insertStringBuilder.append(", ");

        // Add the annotation values
        for (String annotationKey : incomingVertex.getAnnotations().keySet()) {
            if (annotationKey.equalsIgnoreCase("type")) {
                continue;
            }

            String value = (ENABLE_SANITAZATION) ? incomingVertex.getAnnotation(annotationKey).replace("'", "\"") : incomingVertex.getAnnotation(annotationKey);

            insertStringBuilder.append("'");
            insertStringBuilder.append(value);
            insertStringBuilder.append("', ");
        }
        insertString = insertStringBuilder.substring(0, insertStringBuilder.length() - 2) + ")";

        try {
            Statement s = dbConnection.createStatement();
            s.execute(insertString);
            s.close();
            // s.closeOnCompletion();
        } catch (Exception e) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, e);
        }
        return true;
    }

    @Override
    public boolean putEdge(AbstractEdge incomingEdge) {
        int srcVertexHash = incomingEdge.getSourceVertex().hashCode();
        int dstVertexHash = incomingEdge.getDestinationVertex().hashCode();

        // Use StringBuilder to build the SQL insert statement
        StringBuilder insertStringBuilder = new StringBuilder("INSERT INTO " + EDGE_TABLE + " (type, hash, srcVertexHash, dstVertexHash, ");
        for (String annotationKey : incomingEdge.getAnnotations().keySet()) {
            if (annotationKey.equalsIgnoreCase("type")) {
                continue;
            }
            // Sanitize column name to remove special characters
            String newAnnotationKey = sanitizeColumn(annotationKey);

            // As the annotation keys are being iterated, add them as new
            // columns to the table if they do not already exist
            addColumn(EDGE_TABLE, newAnnotationKey);

            insertStringBuilder.append("");
            insertStringBuilder.append(newAnnotationKey);
            insertStringBuilder.append(", ");
        }

        // Eliminate the last 2 characters from the string (", ") and begin adding values
        String insertString = insertStringBuilder.substring(0, insertStringBuilder.length() - 2);
        insertStringBuilder = new StringBuilder(insertString + ") VALUES (");

        // Add the type, hash code, and source and destination vertex Ids
        insertStringBuilder.append("'");
        insertStringBuilder.append(incomingEdge.type());
        insertStringBuilder.append("', ");
        insertStringBuilder.append(incomingEdge.hashCode());
        insertStringBuilder.append(", ");
        insertStringBuilder.append(srcVertexHash);
        insertStringBuilder.append(", ");
        insertStringBuilder.append(dstVertexHash);
        insertStringBuilder.append(", ");

        // Add the annotation values
        for (String annotationKey : incomingEdge.getAnnotations().keySet()) {
            if (annotationKey.equalsIgnoreCase("type")) {
                continue;
            }

            String value = (ENABLE_SANITAZATION) ? incomingEdge.getAnnotation(annotationKey).replace("'", "\"") : incomingEdge.getAnnotation(annotationKey);

            insertStringBuilder.append("'");
            insertStringBuilder.append(value);
            insertStringBuilder.append("', ");
        }
        insertString = insertStringBuilder.substring(0, insertStringBuilder.length() - 2) + ")";

        try {
            Statement s = dbConnection.createStatement();
            s.execute(insertString);
            s.close();
        } catch (Exception e) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, e);
        }
        return true;
    }

    @Override
    public Graph getVertices(String expression) {
        try {
            dbConnection.commit();
            Graph graph = new Graph();
            // assuming that expression is single key value only
            String query = "SELECT * FROM VERTEX WHERE " + expression.replace(":","=");
            Statement vertexStatement = dbConnection.createStatement();
            ResultSet result = vertexStatement.executeQuery(query);
            ResultSetMetaData metadata = result.getMetaData();
            int columnCount = metadata.getColumnCount();

            Map<Integer, String> columnLabels = new HashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                columnLabels.put(i, metadata.getColumnName(i));
            }

            while (result.next()) {
                AbstractVertex vertex = new Vertex();
                vertex.removeAnnotation("type");
                vertex.addAnnotation(columnLabels.get(1), Integer.toString(result.getInt(1)));
                vertex.addAnnotation("type", result.getString(2));
                vertex.addAnnotation(columnLabels.get(3), Integer.toString(result.getInt(3)));
                for (int i = 4; i <= columnCount; i++) {
                    String value = result.getString(i);
                    if ((value != null) && !value.isEmpty()) {
                        vertex.addAnnotation(columnLabels.get(i), result.getString(i));
                    }
                }
                graph.putVertex(vertex);
            }

            graph.commitIndex();
            return graph;
        } catch (Exception ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    public Graph getEdges(String srcVertexAnnotationKey, String srcVertexAnnotationValue, String dstVertexAnnotationKey, String dstVertexAnnotationValue) {
        try {

            dbConnection.commit();
            Graph resultGraph = new Graph();

            Graph srcVertexGraph = getVertices(srcVertexAnnotationKey + ":" + srcVertexAnnotationValue);
            Graph dstVertexGraph = getVertices(dstVertexAnnotationKey + ":" + dstVertexAnnotationValue);

            Iterator<AbstractVertex> iterator = srcVertexGraph.vertexSet().iterator();
            AbstractVertex srcVertex = iterator.next();
            iterator = dstVertexGraph.vertexSet().iterator();
            AbstractVertex dstVertex = iterator.next();

            resultGraph.putVertex(srcVertex);
            resultGraph.putVertex(dstVertex);

            String query = "SELECT * FROM EDGE WHERE srcVertexHash = " + srcVertex.getAnnotation("hash") + " AND dstVertexHash = " + dstVertex.getAnnotation("hash");
            Statement vertexStatement = dbConnection.createStatement();
            ResultSet result = vertexStatement.executeQuery(query);
            ResultSetMetaData metadata = result.getMetaData();
            int columnCount = metadata.getColumnCount();

            Map<Integer, String> columnLabels = new HashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                columnLabels.put(i, metadata.getColumnName(i));
            }

            while (result.next()) {
                AbstractEdge edge = new Edge(srcVertex, dstVertex);
                edge.removeAnnotation("type");
                for (int i=1; i <= columnCount; i++) {
                    String colName = columnLabels.get(i);
                    if (colName != null) {
                        if (colName.equals(ID_STRING) || colName.equals("hash") || colName.equals("srcVertexHash") || colName.equals("dstVertexHash")) {
                            edge.addAnnotation(colName, Integer.toString(result.getInt(i)));
                        } else {
                            edge.addAnnotation(colName, result.getString(i));
                        }
                    }
                    String h = edge.getAnnotation("hash");
                    int x = edge.hashCode();

                }
                resultGraph.putEdge(edge);
            }

            resultGraph.commitIndex();
            return resultGraph;
        } catch (Exception ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    @Override
    public Graph getEdges(int srcvertexId, int dstvertexId) {
        return getEdges(ID_STRING, ""+srcvertexId, ID_STRING, ""+dstvertexId);
    }


    /**********************************************************************************************************/
    /******************************************Changes Made by @raza start***************************************/
    /************************************************************************************************************/

    /*
    * Helper function to find and return a vertex object by ID
    * @param vertexId ID of the vertex to find
    * @return AbstractVertex vertex found against the ID.
    *
    * */
    private AbstractVertex getVertexFromId(int vertexId)
    {
        //TODO: Remove this function and use getVertices in its place
        if (TEST_ENV) {
            for (AbstractVertex v : TEST_GRAPH.vertexSet()) {
                if (v.getAnnotation("vertexId").equals(Integer.toString(vertexId))) {
                    return v;
                }
            }
            return null;
        } else {
            int vertexColumnCount;
            int edgeColumnCount;
            Map<Integer, String> vertexColumnLabels = new HashMap<>();
            Map<Integer, String> edgeColumnLabels = new HashMap<>();
            AbstractVertex vertex = new Vertex();

            try {
                dbConnection.commit();
                String query = "SELECT * FROM vertex WHERE vertexId = " + vertexId;
                Statement vertexStatement = dbConnection.createStatement();
                ResultSet result = vertexStatement.executeQuery(query);
                ResultSetMetaData metadata = result.getMetaData();
                vertexColumnCount = metadata.getColumnCount();

                for (int i = 1; i <= vertexColumnCount; i++) {
                    vertexColumnLabels.put(i, metadata.getColumnName(i));
                }

                result.next();
                vertex.removeAnnotation("type");
                int id = result.getInt(1);
                int hash = result.getInt(3);
                vertex.addAnnotation(vertexColumnLabels.get(1), Integer.toString(id));
                vertex.addAnnotation("type", result.getString(2));
                vertex.addAnnotation(vertexColumnLabels.get(3), Integer.toString(hash));
                for (int i = 4; i <= vertexColumnCount; i++) {
                    String value = result.getString(i);
                    if ((value != null) && !value.isEmpty()) {
                        vertex.addAnnotation(vertexColumnLabels.get(i), result.getString(i));
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
                return null;
            }
            return vertex;
        }
    }


    /*
    *
    * Returns IDs of all neighboring vertices of the given vertex
    * @param vertexId ID of the vertex whose neighbors are to find
    * @param direction Direction of the neighbor from the given vertex
    * @return List<Integer> List of IDs of neighboring vertices
    *
    * */
    private Set<Integer> getNeighborVertexIds(int vertexId, String direction)
    {
        Set<Integer> neighborvertexIds = new HashSet<>();
        if(TEST_ENV)
        {
            for(AbstractEdge e: TEST_GRAPH.edgeSet())
            {
                if(e.getSourceVertex().getAnnotation("vertexId").equals(Integer.toString(vertexId)))
                {
                    neighborvertexIds.add(Integer.parseInt(e.getDestinationVertex().getAnnotation("vertexId")));
                }
            }
        }
        else
        {
            try {
                dbConnection.commit();
                // TODO: Handle exception case when getVertexFromId returns null
                String srcVertex = null;
                String dstVertex = null;
                if(DIRECTION_ANCESTORS.startsWith(direction.toLowerCase())) {
                    srcVertex = "srcVertexHash";
                    dstVertex = "dstVertexHash";
                }
                else if(DIRECTION_DESCENDANTS.startsWith(direction.toLowerCase()))
                {
                    srcVertex = "dstVertexHash";
                    dstVertex = "srcVertexHash";
                }
                String query = "SELECT vertexId FROM vertex WHERE hash IN (SELECT " + dstVertex + " FROM edge WHERE ";
                query +=   srcVertex + " = " + getVertexFromId(vertexId).getAnnotation("hash") + ")";
                Statement statement = dbConnection.createStatement();
                ResultSet result = statement.executeQuery(query);
                while (result.next())
                {
                    neighborvertexIds.add(result.getInt(1));
                }

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        return neighborvertexIds;
    }


    /**
     * Finds paths between src and dst vertices
     * @param sId ID of the current node during traversal
     * @param dId ID of the destination node
     * @param maxPathLength Maximum length of any path to find
     * @param visitedNodes List of nodes visited during traversal
     * @param currentPath Set of vertices explored currently
     * @param allPaths Set of all paths between src and dst
     */
    private void findPath(int sId, int dId, int maxPathLength, Set<Integer> visitedNodes, Stack<AbstractVertex> currentPath, Set<Graph> allPaths)
    {
        if (currentPath.size() > maxPathLength)
            return;

        visitedNodes.add(sId);
        currentPath.push(getVertexFromId(sId));

        if (sId == dId)
        {
            Graph pathGraph = new Graph();
            Iterator<AbstractVertex> iter = currentPath.iterator();
            AbstractVertex previous = iter.next();
            pathGraph.putVertex(previous);
            while(iter.hasNext())
            {
                AbstractVertex curr = iter.next();
                pathGraph.putVertex(curr);
                // find the relevant edges between previous and current vertex
                if(TEST_ENV)
                {
                    for(AbstractEdge e: TEST_GRAPH.edgeSet())
                    {
                        if(e.getSourceVertex().equals(previous) && e.getDestinationVertex().equals(curr))
                        {
                            pathGraph.putEdge(e);
                        }
                    }
                }
                else
                {
                    Graph edges;
                    edges = getEdges("vertexId", previous.getAnnotation("vertexId"), "vertexId", curr.getAnnotation("vertexId"));
                    pathGraph = Graph.union(pathGraph, edges);
                }
                previous = curr;
            }
            allPaths.add(pathGraph);
        }
        else
        {
            for(int neighborId: getNeighborVertexIds(sId, "a"))
            {
                if(!visitedNodes.contains(neighborId))
                {
                    findPath(neighborId, dId, maxPathLength, visitedNodes, currentPath, allPaths);
                }
            }
        }

        currentPath.pop();
        visitedNodes.remove(sId);

    }

    /*
    *
    *  Finds all possible paths between two vertices and returns them.
    *
    *  @param srcvertexId id of the source vertex
    *  @param dstvertexId id of the destination vertex
    *  @param maxLength maximum length of any path found
    *  @return Graph containing all paths between src and dst
    *
    */
    public Graph getAllPaths(int srcvertexId, int dstvertexId, int maxPathLength)
    {
        Graph resultGraph = new Graph();
        Set<Integer> visitedNodes = new HashSet<>();
        Stack<AbstractVertex> currentPath = new Stack<>();
        Set<Graph> allPaths = new HashSet<>();
        try
        {
            // Find path between src and dst vertices
            findPath(srcvertexId, dstvertexId, maxPathLength, visitedNodes, currentPath, allPaths);
            for (Graph path: allPaths)
            {
                resultGraph = Graph.union(resultGraph, path);
            }
        }
        catch (Exception ex)
        {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }


        return resultGraph;
    }

    public Graph getLineage(int srcVertexId, int maxDepth , String direction, int terminatingId)
    {
        Graph resultGraph = new Graph();
        AbstractVertex srcVertex = getVertexFromId(srcVertexId);
        if(maxDepth == 0)
        {
            resultGraph.putVertex(srcVertex);
            return resultGraph;
        }
        Set<Integer> visitedNodes = new HashSet<>();
        Queue<AbstractVertex> queue = new LinkedList<>();
        queue.add(srcVertex);
        for(int depth = 0 ; depth < maxDepth && !queue.isEmpty() ; depth++)
        {
            AbstractVertex node = queue.remove();
            int nodeId = Integer.parseInt(node.getAnnotation("vertexId"));
            resultGraph.putVertex(node);
            visitedNodes.add(nodeId);
            for (int nId : getNeighborVertexIds(nodeId, direction))
            {
                if(nId == terminatingId)
                    continue;
                AbstractVertex neighbor = getVertexFromId(nId);
                resultGraph.putVertex(neighbor);
                Graph edges = getEdges("vertexId",
                        direction.equalsIgnoreCase("a") ? Integer.toString(nodeId) : Integer.toString(nId),
                        "vertexId",
                        direction.equalsIgnoreCase("a") ? Integer.toString(nId) : Integer.toString(nodeId));
                resultGraph = Graph.union(resultGraph, edges);
                queue.add(neighbor);
            }
        }
        return resultGraph;
    }

    private AbstractVertex getVertexFromHash(int hash, int columnCount, Map<Integer, String> columnLabels)
    {
        try {
            dbConnection.commit();
            String query = "SELECT * FROM VERTEX WHERE hash = " + hash;
            Statement vertexStatement = dbConnection.createStatement();
            ResultSet result = vertexStatement.executeQuery(query);
            result.next();
            AbstractVertex vertex = new Vertex();
            vertex.removeAnnotation("type");
            vertex.addAnnotation(columnLabels.get(1), Integer.toString(result.getInt(1)));
            vertex.addAnnotation("type", result.getString(2));
            vertex.addAnnotation(columnLabels.get(3), Integer.toString(result.getInt(3)));
            for (int i = 4; i <= columnCount; i++) {
                String value = result.getString(i);
                if ((value != null) && !value.isEmpty()) {
                    vertex.addAnnotation(columnLabels.get(i), result.getString(i));
                }
            }
            return vertex;
        } catch (Exception ex) {
            Logger.getLogger(SQL.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }
}
