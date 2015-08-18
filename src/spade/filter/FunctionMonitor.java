/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

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
package spade.filter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spade.core.AbstractEdge;
import spade.core.AbstractFilter;
import spade.core.AbstractVertex;
import spade.edge.opm.Used;
import spade.edge.opm.WasGeneratedBy;
import spade.vertex.opm.Artifact;
import spade.vertex.opm.Process;

public class FunctionMonitor extends AbstractFilter {

    HashSet<String> methodsToMonitor; //Set of Methods that we want to monitor
    HashMap<String, Integer> artifacts; //Buffer for Artifacts

    public FunctionMonitor() {
        try {
            artifacts = new HashMap<>();
            methodsToMonitor = new HashSet<>();
        } catch (Exception e) {
            // ADD Exception Handling
        }
    }

    //--hash-- The first argument to the function is tokens[0] which is the filename (DOT format)	
    @Override
    public boolean initialize(String arguments) {
        try {
            HashMap<String, String> nodes = new HashMap<>(); // HashMap of id vs name for nodes
            HashMap<String, String> nodesRev = new HashMap<>(); // HashMap of name vs id for nodes
            HashMap<String, HashSet<String>> edges = new HashMap<>(); // HashMap of HashSet of incoming edges for every node
            LinkedList<String> queue = new LinkedList<>();

            String[] tokens = arguments.split("\\s+");
            BufferedReader br = new BufferedReader(new FileReader(tokens[0]));
            String s;
            Pattern nodeDef = Pattern.compile("([^ \t]+) .*label=\"[{]?([^{}]*)[}]?\".*;"); // Format for node definition in DOT file
            Pattern edgeDef = Pattern.compile("([^ \t]+) -> ([^ \t]+);"); // Format for edge definition in DOT file
            while ((s = br.readLine()) != null) {
                Matcher node = nodeDef.matcher(s);
                Matcher edge = edgeDef.matcher(s);
                if (node.find()) // If Node Definition
                {
                    nodes.put(node.group(2), node.group(1));
                    nodesRev.put(node.group(1), node.group(2));
                } else if (edge.find()) // If Edge Definition
                {
                    if (!edges.containsKey(edge.group(2))) {
                        edges.put(edge.group(2), new HashSet<String>());
                    }
                    edges.get(edge.group(2)).add(edge.group(1));
                }
            }
            br.close();

            String traceFunctions[] = tokens[1].split("\\s+");
            for (int i = 0; i < traceFunctions.length; i++) {
                queue.add(nodes.get(traceFunctions[i]));
            }

            while (queue.size() != 0) //Breadth First Search
            {
                String name = queue.removeFirst();
                String actualName = nodesRev.get(name);
                if (!methodsToMonitor.contains(actualName)) {
                    methodsToMonitor.add(actualName);
                    if (edges.containsKey(name)) {
                        queue.addAll(edges.get(name));
                    }
                }
            }

            //--hash-- You can print out methodsToMonitor to a file from here
            if (tokens.length > 2) {
                BufferedWriter bw = new BufferedWriter(new FileWriter(tokens[2]));

                for (String key : methodsToMonitor) {
                    bw.write(key + "\n");
                }

                bw.close();
            }

            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    //--hash-- The first argument to the function is tokens[0] which is the filename (DOT format)	
    public boolean initialize2(String arguments) {
        try {
            HashMap<String, String> nodes = new HashMap<>(); // HashMap of id vs name for nodes
            HashMap<String, String> nodesRev = new HashMap<>(); // HashMap of name vs id for nodes
            HashMap<String, HashSet<String>> edges = new HashMap<>(); // HashMap of HashSet of incoming edges for every node
            LinkedList<String> queue = new LinkedList<>();

            String[] tokens = arguments.split("\\s+");
            BufferedReader br = new BufferedReader(new FileReader(tokens[0]));
            String s;
            Pattern nodeDef = Pattern.compile("([^ \t]+) .*label=\"[{]?([^{}]*)[}]?\".*;"); // Format for node definition in DOT file
            Pattern edgeDef = Pattern.compile("([^ \t]+) -> ([^ \t]+);"); // Format for edge definition in DOT file
            while ((s = br.readLine()) != null) {
                Matcher node = nodeDef.matcher(s);
                Matcher edge = edgeDef.matcher(s);
                if (node.find()) // If Node Definition
                {
                    nodes.put(node.group(2), node.group(1));
                    nodesRev.put(node.group(1), node.group(2));
                } else if (edge.find()) // If Edge Definition
                {
                    if (!edges.containsKey(edge.group(2))) {
                        edges.put(edge.group(2), new HashSet<String>());
                    }
                    edges.get(edge.group(2)).add(edge.group(1));
                }
            }
            br.close();

            br = new BufferedReader(new FileReader(tokens[1]));
            String line;
            while ((line = br.readLine()) != null) {
                queue.add(nodes.get(line));
            }
            br.close();

            while (queue.size() != 0) //Breadth First Search
            {
                String name = queue.removeFirst();
                String actualName = nodesRev.get(name);
                if (!methodsToMonitor.contains(actualName)) {
                    methodsToMonitor.add(actualName);
                    if (edges.containsKey(name)) {
                        queue.addAll(edges.get(name));
                    }
                }
            }

            //--hash-- You can print out methodsToMonitor to a file from here
            if (tokens.length > 2) {

                BufferedWriter bw = new BufferedWriter(new FileWriter(tokens[2]));
                for (String key : methodsToMonitor) {
                    bw.write(key + "\n");
                }
                bw.close();
            }

            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public void putVertex(AbstractVertex incoming) {
        if (incoming instanceof Process) {
            if (methodsToMonitor.contains(incoming.getAnnotation("FunctionName"))) {
                putInNextFilter(incoming);
            }
        } else {
            String ID = incoming.getAnnotation("ID");
            artifacts.put(ID, 1);
        }
    }

    @Override
    public void putEdge(AbstractEdge incoming) {
        if (incoming instanceof Used) {
            Artifact artifact = (Artifact) incoming.getDestinationVertex();
            Process process = (Process) incoming.getSourceVertex();
            String ArgID = artifact.getAnnotation("ID");
            if (methodsToMonitor.contains(process.getAnnotation("FunctionName"))) {
                if (artifacts.containsKey(ArgID)) // Every Artifact is used at most twice
                {
                    if (artifacts.get(ArgID) == 1) // Every Artifact is used at most twice
                    {
                        artifacts.put(ArgID, artifacts.get(ArgID) + 1); // Increment Counter
                        putInNextFilter(artifact);
                    } else {
                        artifacts.remove(ArgID); // If Artifact seen twice remove it from the HashMap
                    }
                    putInNextFilter(incoming);
                }
            } else {
                artifacts.remove(ArgID);  // If we do not want to monitor the Artifact remove it from the HashMap
            }

        } else if (incoming instanceof WasGeneratedBy) {
            Process process = (Process) incoming.getDestinationVertex();
            Artifact artifact = (Artifact) incoming.getSourceVertex();
            String ArgID = artifact.getAnnotation("ID");
            if (methodsToMonitor.contains(process.getAnnotation("FunctionName"))) {
                if (artifacts.containsKey(ArgID)) {
                    if (artifacts.get(ArgID) == 1) {
                        artifacts.put(ArgID, artifacts.get(ArgID) + 1);
                        putInNextFilter(artifact);
                    } else {
                        artifacts.remove(ArgID);
                    }
                    putInNextFilter(incoming);
                }
            } else {
                artifacts.remove(ArgID);
            }
        } else // WasTriggeredBy
        {
            AbstractVertex source = incoming.getSourceVertex();
            AbstractVertex destination = incoming.getDestinationVertex();
            if (methodsToMonitor.contains(source.getAnnotation("FunctionName"))) {
                if (methodsToMonitor.contains(destination.getAnnotation("FunctionName"))) {
                    putInNextFilter(incoming);
                }
            }
        }
    }

    public static void main(String args[]) {

        String dotFileName = args[0];
        String functionFileName = args[1];
        String outFileName = args[2];

        String arguments = dotFileName + " " + functionFileName + " " + outFileName;

        FunctionMonitor llvmFilter = new FunctionMonitor();
        llvmFilter.initialize2(arguments);

        System.out.println("The methods to monitor are written out to the file : " + outFileName);
    }

}
