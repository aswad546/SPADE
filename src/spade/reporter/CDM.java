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
package spade.reporter;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.codec.binary.Hex;

import com.bbn.tc.schema.avro.cdm18.AbstractObject;
import com.bbn.tc.schema.avro.cdm18.Event;
import com.bbn.tc.schema.avro.cdm18.FileObject;
import com.bbn.tc.schema.avro.cdm18.Host;
import com.bbn.tc.schema.avro.cdm18.HostIdentifier;
import com.bbn.tc.schema.avro.cdm18.HostType;
import com.bbn.tc.schema.avro.cdm18.Interface;
import com.bbn.tc.schema.avro.cdm18.MemoryObject;
import com.bbn.tc.schema.avro.cdm18.NetFlowObject;
import com.bbn.tc.schema.avro.cdm18.Principal;
import com.bbn.tc.schema.avro.cdm18.SHORT;
import com.bbn.tc.schema.avro.cdm18.SrcSinkObject;
import com.bbn.tc.schema.avro.cdm18.Subject;
import com.bbn.tc.schema.avro.cdm18.TCCDMDatum;
import com.bbn.tc.schema.avro.cdm18.UUID;
import com.bbn.tc.schema.avro.cdm18.UnitDependency;
import com.bbn.tc.schema.avro.cdm18.UnnamedPipeObject;

import spade.core.AbstractReporter;
import spade.core.AbstractVertex;
import spade.core.Settings;
import spade.edge.cdm.SimpleEdge;
import spade.reporter.audit.OPMConstants;
import spade.utility.CommonFunctions;
import spade.utility.ExternalMemoryMap;
import spade.utility.FileUtility;
import spade.utility.Hasher;

/**
 * CDM reporter that reads output of CDM json storage.
 *	
 * Assumes that all vertices are seen before the edges they are a part of.
 * If a vertex is not found then edge is not put.
 *
 */
public class CDM extends AbstractReporter{
	
	private final Logger logger = Logger.getLogger(this.getClass().getName());
	
	// Keys used in config
	private static final String CONFIG_KEY_CACHE_DATABASE_PARENT_PATH = "cacheDatabasePath",
								CONFIG_KEY_CACHE_DATABASE_NAME = "verticesDatabaseName",
								CONFIG_KEY_CACHE_SIZE = "verticesCacheSize",
								CONFIG_KEY_BLOOMFILTER_FALSE_PROBABILITY = "verticesBloomfilterFalsePositiveProbability",
								CONFIG_KEY_BLOOMFILTER_EXPECTED_ELEMENTS = "verticesBloomFilterExpectedNumberOfElements",
								CONFIG_KEY_SCHEMA = "Schema";
	
	
	//Reporting variables
	private boolean reportingEnabled = false;
	private long reportEveryMs;
	private long lastReportedTime;
	private long linesRead = 0;

	private volatile boolean shutdown = false;
	
	// Using an external map because can grow arbitrarily
	private ExternalMemoryMap<String, AbstractVertex> uuidToVertexMap;
	private final String uuidMapId = "CDM[UUID2VertexMap]";
	
	private LinkedList<DataReader> dataReaders = new LinkedList<DataReader>();
	private boolean waitForLog = true;
		
	// The main thread that processes the file
	private Thread datumProcessorThread = new Thread(new Runnable(){
		@Override
		public void run(){
			boolean shutdownCalledAndSucceeded = false;
			try{
				while(!dataReaders.isEmpty()){
					DataReader dataReader = dataReaders.removeFirst();
					String currentFilePath = dataReader.getDataFilePath();
					logger.log(Level.INFO, "Started reading file: " + currentFilePath);
					TCCDMDatum tccdmDatum = null;
					while((tccdmDatum = (TCCDMDatum)dataReader.read()) != null){
						if(shutdown && !waitForLog){
							shutdownCalledAndSucceeded = true;
							logger.log(Level.INFO, "Shutting down the data reader thread");
							break;
						}
						Object datum = tccdmDatum.getDatum();
						processDatum(datum);
					}
					try{
						dataReader.close();
					}catch(Exception e){
						logger.log(Level.WARNING, "Continuing but FAILED to close data reader for file: " + 
								currentFilePath, e);
					}
					if(shutdownCalledAndSucceeded){ // break out of the outer loop too
						break;
					}
					logger.log(Level.INFO, "Finished reading file: " + currentFilePath);
				}
				if(!shutdownCalledAndSucceeded){ // If shutdown not called
					logger.log(Level.INFO, "Finished reading all file(s)");
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Stopping because of reading/processing error", e);
			}
			// Here either because of exception, shutdown, or all files read.
			doCleanup();
			logger.log(Level.INFO, "Exiting data reader thread");
		}
	}, "CDM-Reporter");
	
	private Map<String, String> readDefaultConfigFile(){
		try{
			return FileUtility.readConfigFileAsKeyValueMap(
					Settings.getDefaultConfigFilePath(this.getClass()),
					"="
					);
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to load config file", e);
			return null;
		}
	}
	
	private ExternalMemoryMap<String, AbstractVertex> initCacheMap(String tempDirPath, String verticesDatabaseName, String verticesCacheSize,
			String verticesBloomfilterFalsePositiveProbability, String verticesBloomfilterExpectedNumberOfElements){
		try{
			return CommonFunctions.createExternalMemoryMapInstance(uuidMapId, verticesCacheSize, 
					verticesBloomfilterFalsePositiveProbability, verticesBloomfilterExpectedNumberOfElements, tempDirPath, 
					verticesDatabaseName, null, new Hasher<String>(){
						@Override
						public String getHash(String t) {
							return t;
						}
					});
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to create external map", e);
			return null;
		}
	}
	
	private void initReporting(String reportingIntervalSecondsConfig){
		if(reportingIntervalSecondsConfig != null){
			Integer reportingIntervalSeconds = CommonFunctions.parseInt(reportingIntervalSecondsConfig.trim(), null);
			if(reportingIntervalSeconds != null){
				reportingEnabled = true;
				reportEveryMs = reportingIntervalSeconds * 1000;
				lastReportedTime = System.currentTimeMillis();
			}else{
				logger.log(Level.WARNING, "Invalid reporting interval. Reporting disabled.");
			}
		}
	}
	
	@Override
	public boolean launch(String arguments){
		Map<String, String> argsMap = CommonFunctions.parseKeyValPairs(arguments);
		
		String inputFileArgument = argsMap.get("inputFile");
		String rotateArgument = argsMap.get("rotate");
		String waitForLogArgument = argsMap.get("waitForLog");
		
		if(CommonFunctions.isNullOrEmpty(inputFileArgument)){
			logger.log(Level.SEVERE, "NULL/Empty 'inputFile' argument: " + inputFileArgument);
			return false;
		}else{
			inputFileArgument = inputFileArgument.trim();
			File inputFile = null;
			try{
				inputFile = new File(inputFileArgument);
				if(!inputFile.exists()){
					logger.log(Level.SEVERE, "No file at path: " + inputFileArgument);
					return false;
				}
				if(!inputFile.isFile()){
					logger.log(Level.SEVERE, "Not a regular file at path: " + inputFileArgument);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check if file exists: " + inputFileArgument, e);
				return false;
			}
			boolean rotate = false;
			if(rotateArgument != null){
				if(rotateArgument.equalsIgnoreCase("true")){
					rotate = true;
				}else if(rotateArgument.equalsIgnoreCase("false")){
					rotate = false;
				}else{
					logger.log(Level.SEVERE, "Invalid 'rotate' (only 'true'/'false') argument: " + rotateArgument);
					return false;
				}
			}
			if(waitForLogArgument != null){
				if(waitForLogArgument.equalsIgnoreCase("true")){
					waitForLog = true;
				}else if(waitForLogArgument.equalsIgnoreCase("false")){
					waitForLog = false;
				}else{
					logger.log(Level.SEVERE, "Invalid 'waitForLog' (only 'true'/'false') argument: " + waitForLogArgument);
					return false;
				}
			}
			LinkedList<String> inputFilePaths = new LinkedList<String>(); // ordered
			inputFilePaths.addLast(inputFile.getAbsolutePath());
			if(rotate){
				try{
					String inputFileParentPath = inputFile.getParentFile().getAbsolutePath();
					String inputFileName = inputFile.getName();
					int totalFilesCount = inputFile.getParentFile().list().length;
					for(int a = 1; a < totalFilesCount; a++){
						File file = new File(inputFileParentPath + File.separatorChar + inputFileName + "." + a);
						if(file.exists()){
							inputFilePaths.addLast(file.getAbsolutePath());
						}
					}
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to gather all input files", e);
					return false;
				}
			}
			
			String schemaFilePath = null;
			Map<String, String> configMap = readDefaultConfigFile();
			if(configMap == null || configMap.isEmpty()){
				logger.log(Level.SEVERE, "NULL/Empty config map: " + configMap);
				return false;
			}else{
				schemaFilePath = configMap.get(CONFIG_KEY_SCHEMA);
				if(CommonFunctions.isNullOrEmpty(schemaFilePath)){
					logger.log(Level.SEVERE, "NULL/Empty '"+CONFIG_KEY_SCHEMA+"' in config file: "+schemaFilePath);
					return false;
				}else{
					schemaFilePath = schemaFilePath.trim();
					try{
						File schemaFile = new File(schemaFilePath);
						if(!schemaFile.exists()){
							logger.log(Level.SEVERE, "Schema file doesn't exist: " + schemaFilePath);
							return false;
						}
						if(!schemaFile.isFile()){
							logger.log(Level.SEVERE, "Schema path is not a regular file: " + schemaFilePath);
							return false;
						}
					}catch(Exception e){
						logger.log(Level.SEVERE, "Failed to check if schema file exists: " + schemaFilePath, e);
						return false;
					}
				}
			}
			
			try{
				boolean binaryFormat = false;
				if(inputFileArgument.endsWith(".json")){
					binaryFormat = false;
				}else{
					binaryFormat = true;
				}
				for(String inputFilePath : inputFilePaths){
					DataReader dataReader = null;
					if(binaryFormat){
						dataReader = new BinaryReader(inputFilePath, schemaFilePath);
					}else{
						dataReader = new JsonReader(inputFilePath, schemaFilePath);
					}
					dataReaders.addLast(dataReader);
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to build data reader", e);
				return false;
			}
			
			initReporting(configMap.get("reportingIntervalSeconds"));
			
			try{
				uuidToVertexMap = initCacheMap(configMap.get(CONFIG_KEY_CACHE_DATABASE_PARENT_PATH), 
						configMap.get(CONFIG_KEY_CACHE_DATABASE_NAME), configMap.get(CONFIG_KEY_CACHE_SIZE), 
						configMap.get(CONFIG_KEY_BLOOMFILTER_FALSE_PROBABILITY), 
						configMap.get(CONFIG_KEY_BLOOMFILTER_EXPECTED_ELEMENTS));
				if(uuidToVertexMap == null){
					logger.log(Level.SEVERE, "NULL external memory map");
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to create external memory map", e);
				return false;
			}
			
			try{
				datumProcessorThread.start();
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to start data processor thread", e);
				doCleanup();
				return false;
			}
			
			logger.log(Level.INFO, 
					"Arguments: rotate='"+rotate+"', waitForLog='"+waitForLog+"', inputFile='"+inputFileArgument+"'");
			logger.log(Level.INFO, "Input files: " + inputFilePaths);
			
			return true;
		}
	}
	
	private void printStats(){
		Runtime runtime = Runtime.getRuntime();
		long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024*1024);   	
		long internalBufferSize = getBuffer().size();
		logger.log(Level.INFO, "Lines read: {0}, Internal buffer size: {1}, JVM memory in use: {2}MB", new Object[]{linesRead, internalBufferSize, usedMemoryMB});
	}
	
	private void processEvent(Event event){
		Map<String, String> edgeKeyValues = new HashMap<String, String>();
		if(event.getSequence() != null){
			edgeKeyValues.put("sequence", String.valueOf(event.getSequence()));
		}
		if(event.getType() != null){
			edgeKeyValues.put("cdm.type", String.valueOf(event.getType()));
		}
		if(event.getThreadId() != null){
			edgeKeyValues.put("threadId", String.valueOf(event.getThreadId()));
		}
		if(event.getTimestampNanos() != null){
			edgeKeyValues.put("timestampNanos", String.valueOf(event.getTimestampNanos()));
		}
		if(event.getLocation() != null){
			edgeKeyValues.put("location", String.valueOf(event.getLocation()));
		}
		if(event.getSize() != null){
			edgeKeyValues.put("size", String.valueOf(event.getSize()));
		}
		edgeKeyValues.putAll(getValuesFromPropertiesMap(event.getProperties()));
		
		String opmValue = null, operationValue = null;
		
		UUID src1Uuid = null, dst1Uuid = null, // process to/from primary
				src2Uuid = null, dst2Uuid = null, //process to/from secondary
				src3Uuid = null, dst3Uuid = null; //primary to/from secondary
		
		switch (event.getType()) {
			case EVENT_OTHER:
				operationValue = edgeKeyValues.get(OPMConstants.EDGE_OPERATION);
				if(OPMConstants.OPERATION_TEE.equals(operationValue) 
						|| OPMConstants.OPERATION_SPLICE.equals(operationValue)){
					src1Uuid = event.getSubject();
					dst1Uuid = event.getPredicateObject();
					
					src2Uuid = event.getPredicateObject2();
					dst2Uuid = event.getSubject();
					
					src3Uuid = event.getPredicateObject2();
					dst3Uuid = event.getPredicateObject();
				}else if(OPMConstants.OPERATION_VMSPLICE.equals(operationValue)){
					src1Uuid = event.getPredicateObject();
					dst1Uuid = event.getSubject();
				}else if(OPMConstants.OPERATION_INIT_MODULE.equals(operationValue)
						|| OPMConstants.OPERATION_FINIT_MODULE.equals(operationValue)){
					src1Uuid = event.getSubject();
					dst1Uuid = event.getPredicateObject();
				}
				break;
			case EVENT_OPEN:
			case EVENT_CLOSE:
				opmValue = edgeKeyValues.get(OPMConstants.OPM);
			case EVENT_LOADLIBRARY:
			case EVENT_RECVMSG:
			case EVENT_RECVFROM:
			case EVENT_READ:
			case EVENT_ACCEPT:
				if(opmValue != null){
					if(opmValue.equals(OPMConstants.USED)){
						src1Uuid = event.getSubject();
						dst1Uuid = event.getPredicateObject();
					}else if(opmValue.equals(OPMConstants.WAS_GENERATED_BY)){
						src1Uuid = event.getPredicateObject();
						dst1Uuid = event.getSubject();
					}
				}else{
					src1Uuid = event.getSubject();
					dst1Uuid = event.getPredicateObject();
				}
				break;					
			case EVENT_EXIT:
			case EVENT_UNIT:
			case EVENT_FORK:
			case EVENT_EXECUTE:
			case EVENT_CLONE:
			case EVENT_CHANGE_PRINCIPAL:
				src1Uuid = event.getPredicateObject();
				dst1Uuid = event.getSubject();
				break;								
			case EVENT_CONNECT:
			case EVENT_CREATE_OBJECT:
			case EVENT_WRITE:
			case EVENT_MPROTECT:
			case EVENT_SENDTO:
			case EVENT_SENDMSG:
			case EVENT_UNLINK:
			case EVENT_MODIFY_FILE_ATTRIBUTES:
			case EVENT_TRUNCATE:
				src1Uuid = event.getPredicateObject();
				dst1Uuid = event.getSubject();
				break;								
			case EVENT_LINK:
			case EVENT_RENAME:
			case EVENT_MMAP:
			case EVENT_UPDATE:
				src1Uuid = event.getSubject();
				dst1Uuid = event.getPredicateObject();
				
				src2Uuid = event.getPredicateObject2();
				dst2Uuid = event.getSubject();
				
				src3Uuid = event.getPredicateObject2();
				dst3Uuid = event.getPredicateObject();
				break;
			default:
				logger.log(Level.WARNING, "Unexpected Event type: " + event.getType());
				return;
		}
		
		if(src1Uuid != null && dst1Uuid != null){
			SimpleEdge edge = new SimpleEdge(
					uuidToVertexMap.get(getUUIDAsString(src1Uuid)),
					uuidToVertexMap.get(getUUIDAsString(dst1Uuid)));
			edge.addAnnotations(edgeKeyValues);
			putEdge(edge);
		}
		if(src2Uuid != null && dst2Uuid != null){
			SimpleEdge edge = new SimpleEdge(
					uuidToVertexMap.get(getUUIDAsString(src2Uuid)),
					uuidToVertexMap.get(getUUIDAsString(dst2Uuid)));
			edge.addAnnotations(edgeKeyValues);
			putEdge(edge);
		}
		if(src3Uuid != null && dst3Uuid != null){
			SimpleEdge edge = new SimpleEdge(
					uuidToVertexMap.get(getUUIDAsString(src3Uuid)),
					uuidToVertexMap.get(getUUIDAsString(dst3Uuid)));
			edge.addAnnotations(edgeKeyValues);
			putEdge(edge);
		}
	}
	
	private void processDatum(Object datum){
		if(reportingEnabled){
			linesRead++;
			long currentTime = System.currentTimeMillis();
			if((currentTime - lastReportedTime) >= reportEveryMs){
				printStats();
				lastReportedTime = currentTime;
			}
		}
		
		if(datum != null){
			Class<?> datumClass = datum.getClass();
			if(datumClass.equals(UnitDependency.class)){
				UnitDependency unitDependency = (UnitDependency)datum;
				UUID unitUuid = unitDependency.getUnit();//dst
				UUID dependentUnitUuid = unitDependency.getDependentUnit();//src
				String unitUuidString = getUUIDAsString(unitUuid);
				String dependentUnitUuidString = getUUIDAsString(dependentUnitUuid);
				AbstractVertex unitVertex = uuidToVertexMap.get(unitUuidString);
				AbstractVertex dependentUnitVertex = uuidToVertexMap.get(dependentUnitUuidString);
				spade.edge.cdm.SimpleEdge edge = new spade.edge.cdm.SimpleEdge(dependentUnitVertex, unitVertex);
				putEdge(edge);
			}else if(datumClass.equals(Event.class)){
				Event event = (Event)datum;
				processEvent(event);				
			}else{
				
				UUID uuid = null;
				AbstractVertex vertex = null;
				UUID principalUuid = null;
				
				if(datumClass.equals(Subject.class)){
					vertex = new spade.vertex.cdm.Subject();
					Subject subject = (Subject)datum;
					uuid = subject.getUuid();
					if(subject.getCid() != null){
						vertex.addAnnotation("pid", String.valueOf(subject.getCid()));
					}
					if(subject.getParentSubject() != null){
						vertex.addAnnotation("parentSubjectUuid", getUUIDAsString(subject.getParentSubject()));
					}
					if(subject.getLocalPrincipal() != null){
						vertex.addAnnotation("localPrincipal", getUUIDAsString(subject.getLocalPrincipal()));
					}
					principalUuid = subject.getLocalPrincipal();
					if(subject.getStartTimestampNanos() != null){
						vertex.addAnnotation("startTimestampNanos", String.valueOf(subject.getStartTimestampNanos()));
					}
					if(subject.getUnitId() != null){
						vertex.addAnnotation("unitId", String.valueOf(subject.getUnitId()));
					}
					if(subject.getIteration() != null){
						vertex.addAnnotation("iteration", String.valueOf(subject.getIteration()));
					}
					if(subject.getCount() != null){
						vertex.addAnnotation("count", String.valueOf(subject.getCount()));
					}
					if(subject.getCmdLine() != null){
						vertex.addAnnotation("cmdLine", String.valueOf(subject.getCmdLine()));
					}
					vertex.addAnnotations(getValuesFromPropertiesMap(subject.getProperties()));
					if(subject.getType() != null){
						vertex.addAnnotation("cdm.type", String.valueOf(subject.getType()));
					}
				}else if(datumClass.equals(Principal.class)){
					vertex = new spade.vertex.cdm.Principal();
					Principal principal = (Principal)datum;
					uuid = principal.getUuid();
					if(principal.getUserId() != null){
						vertex.addAnnotation("userId", String.valueOf(principal.getUserId()));
					}
					List<CharSequence> groupIds = principal.getGroupIds();
					if(groupIds.size() > 0){
						vertex.addAnnotation("gid", String.valueOf(groupIds.get(0)));
					}
					if(groupIds.size() > 1){
						vertex.addAnnotation("egid", String.valueOf(groupIds.get(1)));
					}
					if(groupIds.size() > 2){
						vertex.addAnnotation("sgid", String.valueOf(groupIds.get(2)));
					}
					if(groupIds.size() > 3){
						vertex.addAnnotation("fsgid", String.valueOf(groupIds.get(3)));
					}
					vertex.addAnnotations(getValuesFromPropertiesMap(principal.getProperties()));
					vertex.addAnnotation("cdm.type", "Principal");
				}else { // artifacts
					vertex = new spade.vertex.cdm.Object();
					AbstractObject baseObject = null;
					if(datumClass.equals(MemoryObject.class)){
						MemoryObject memoryObject = (MemoryObject)datum;
						uuid = memoryObject.getUuid();
						baseObject = memoryObject.getBaseObject();
						if(memoryObject.getMemoryAddress() != null){
							vertex.addAnnotation("memoryAddress", String.valueOf(memoryObject.getMemoryAddress()));
						}
						if(memoryObject.getSize() != null){
							vertex.addAnnotation("size", String.valueOf(memoryObject.getSize()));
						}
						vertex.addAnnotation("cdm.type", "MemoryObject");
					}else if(datumClass.equals(NetFlowObject.class)){
						NetFlowObject netFlowObject = (NetFlowObject)datum;
						uuid = netFlowObject.getUuid();
						baseObject = netFlowObject.getBaseObject();
						if(netFlowObject.getLocalAddress() != null){
							vertex.addAnnotation("localAddress", String.valueOf(netFlowObject.getLocalAddress()));
						}
						if(netFlowObject.getLocalPort() != null){
							vertex.addAnnotation("localPort", String.valueOf(netFlowObject.getLocalPort()));
						}
						if(netFlowObject.getRemoteAddress() != null){
							vertex.addAnnotation("remoteAddress", String.valueOf(netFlowObject.getRemoteAddress()));
						}
						if(netFlowObject.getRemotePort() != null){
							vertex.addAnnotation("remotePort", String.valueOf(netFlowObject.getRemotePort()));
						}
						if(netFlowObject.getIpProtocol() != null){
							vertex.addAnnotation("ipProtocol", String.valueOf(netFlowObject.getIpProtocol()));
						}
						vertex.addAnnotation("cdm.type", "NetFlowObject");
					}else if(datumClass.equals(SrcSinkObject.class)){
						SrcSinkObject srcSinkObject = (SrcSinkObject)datum;
						// unknown
						uuid = srcSinkObject.getUuid();
						baseObject = srcSinkObject.getBaseObject();
						if(srcSinkObject.getFileDescriptor() != null){
							vertex.addAnnotation("fileDescriptor", String.valueOf(srcSinkObject.getFileDescriptor()));
						}
						vertex.addAnnotation("cdm.type", "SrcSinkObject");
					}else if(datumClass.equals(UnnamedPipeObject.class)){
						UnnamedPipeObject unnamedPipeObject = (UnnamedPipeObject)datum;
						uuid = unnamedPipeObject.getUuid();
						baseObject = unnamedPipeObject.getBaseObject();
						if(unnamedPipeObject.getSourceFileDescriptor() != null){
							vertex.addAnnotation("sourceFileDescriptor", String.valueOf(unnamedPipeObject.getSourceFileDescriptor()));
						}
						if(unnamedPipeObject.getSinkFileDescriptor() != null){
							vertex.addAnnotation("sinkFileDescriptor", String.valueOf(unnamedPipeObject.getSinkFileDescriptor()));
						}
						vertex.addAnnotation("cdm.type", "UnnamedPipeObject");
					}else if(datumClass.equals(FileObject.class)){
						FileObject fileObject = (FileObject)datum;
						uuid = fileObject.getUuid();
						baseObject = fileObject.getBaseObject();
						if(fileObject.getType() != null){
							vertex.addAnnotation("cdm.type", String.valueOf(fileObject.getType()));
						}
					}else if(datumClass.equals(Host.class)){
						Host hostObject = (Host)datum;
						uuid = hostObject.getUuid();
						CharSequence hostName = hostObject.getHostName();
						CharSequence osDetails = hostObject.getOsDetails();
						HostType hostType = hostObject.getHostType();
						List<HostIdentifier> hostIdentifiers = hostObject.getHostIdentifiers();
						List<Interface> interfaces = hostObject.getInterfaces();
						vertex.addAnnotation("hostName", String.valueOf(hostName));
						vertex.addAnnotation("osDetails", String.valueOf(osDetails));
						vertex.addAnnotation("hostType", String.valueOf(hostType));
						if(hostIdentifiers != null){
							for(HostIdentifier hostIdentifier : hostIdentifiers){
								if(hostIdentifier != null){
									vertex.addAnnotation(String.valueOf(hostIdentifier.getIdType()), 
											String.valueOf(hostIdentifier.getIdValue()));
								}
							}
						}
						if(interfaces != null){
							for(Interface interfaze : interfaces){
								if(interfaze != null){
									vertex.addAnnotation("name", String.valueOf(interfaze.getName()));
									vertex.addAnnotation("macAddress", String.valueOf(interfaze.getMacAddress()));
									vertex.addAnnotation("ipAddresses", String.valueOf(interfaze.getIpAddresses()));
								}
							}
						}
						vertex.addAnnotation("cdm.type", "Host");
					}
					vertex.addAnnotations(getValuesFromArtifactAbstractObject(baseObject));
				}
				if(uuid != null && vertex != null){
					String uuidString = getUUIDAsString(uuid);
					vertex.addAnnotation("uuid", uuidString);
					uuidToVertexMap.put(uuidString, vertex);
					putVertex(vertex);
					if(principalUuid != null){
						AbstractVertex principalVertex = uuidToVertexMap.get(getUUIDAsString(principalUuid));
						if(principalVertex != null){
							SimpleEdge edge = new SimpleEdge(vertex, principalVertex);
							putEdge(edge);
						}
					}
				}
			}
		}		
	}
	
	private String getUUIDAsString(UUID uuid){
		if(uuid != null){
			return Hex.encodeHexString(uuid.bytes());
		}
		return null;
	}
	
	private String getPermissionSHORTAsString(SHORT permission){
		if(permission == null){
			return null;
		}else{
			ByteBuffer bb = ByteBuffer.allocate(2);
			bb.put(permission.bytes()[0]);
			bb.put(permission.bytes()[1]);
			int permissionShort = bb.getShort(0);
			return Integer.toOctalString(permissionShort);
		}
	}
	
	private Map<String, String> getValuesFromArtifactAbstractObject(AbstractObject object){
		Map<String, String> keyValues = new HashMap<String, String>();
		if(object != null){
			if(object.getEpoch() != null){
				keyValues.put("epoch", String.valueOf(object.getEpoch()));
			}
			if(object.getPermission() != null){
				keyValues.put("permission", new String(getPermissionSHORTAsString(object.getPermission())));
			}
			keyValues.putAll(getValuesFromPropertiesMap(object.getProperties()));
		}
		return keyValues;
	}
	
	private Map<String, String> getValuesFromPropertiesMap(Map<CharSequence, CharSequence> propertiesMap){
		Map<String, String> keyValues = new HashMap<String, String>();
		if(propertiesMap != null){
			propertiesMap.entrySet().forEach(
					entry -> {
							if(entry.getValue() != null){
								keyValues.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
							}
						}
					);
		}
		return keyValues;
	}
	
	@Override
	public boolean shutdown(){
		shutdown = true;
		if(waitForLog){
			logger.log(Level.INFO, "Going to shutdown after all files read.");
		}else{
			logger.log(Level.INFO, "Going to shutdown right now.");
		}
		return true;
	}
	
	private synchronized void doCleanup(){
		if(uuidToVertexMap != null){
			CommonFunctions.closePrintSizeAndDeleteExternalMemoryMap(uuidMapId, uuidToVertexMap);
			uuidToVertexMap = null;
		}

		if(dataReaders != null){
			while(!dataReaders.isEmpty()){
				DataReader dataReader = dataReaders.removeFirst();
				if(dataReader != null){
					try{
						dataReader.close();
					}catch(Exception e){
						logger.log(Level.WARNING, "Failed to close data reader for file: " + 
								dataReader.getDataFilePath(), e);
					}
				}
			}
		}
	}	
}

interface DataReader{

	/**
	 * Must return null to indicate EOF
	 * 
	 * @return TCCDMDatum object
	 * @throws Exception
	 */
	public Object read() throws Exception;
	
	public void close() throws Exception;
	
	/**
	 * @return The data file being read
	 */
	public String getDataFilePath();
	
}

class JsonReader implements DataReader{
	
	private String filepath;
	private DatumReader<Object> datumReader;
	private Decoder decoder;
	
	public JsonReader(String dataFilepath, String schemaFilepath) throws Exception{
		this.filepath = dataFilepath;
		Parser parser = new Schema.Parser();
		Schema schema = parser.parse(new File(schemaFilepath));
		this.datumReader = new SpecificDatumReader<Object>(schema);
		this.decoder = DecoderFactory.get().jsonDecoder(schema, 
				new FileInputStream(new File(dataFilepath)));
	}
	
	public Object read() throws Exception{
		try{
			return datumReader.read(null, decoder);
		}catch(EOFException eof){
			return null;
		}catch(Exception e){
			throw e;
		}
	}
	
	public void close() throws Exception{
		// Nothing
	}
	
	public String getDataFilePath(){
		return filepath;
	}
	
}

class BinaryReader implements DataReader{
	
	private String filepath;
	private DataFileReader<Object> dataFileReader;
	
	public BinaryReader(String dataFilepath, String schemaFilepath) throws Exception{
		this.filepath = dataFilepath;
		Parser parser = new Schema.Parser();
		Schema schema = parser.parse(new File(schemaFilepath));
		DatumReader<Object> datumReader = new SpecificDatumReader<Object>(schema);
		this.dataFileReader = new DataFileReader<>(new File(dataFilepath), datumReader);
	}
	
	public Object read() throws Exception{
		if(dataFileReader.hasNext()){
			return dataFileReader.next();
		}else{
			return null;
		}
	}
	
	public void close() throws Exception{
		dataFileReader.close();
	}
	
	public String getDataFilePath(){
		return filepath;
	}
}
