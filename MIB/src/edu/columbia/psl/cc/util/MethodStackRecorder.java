package edu.columbia.psl.cc.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;

import org.apache.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import com.google.gson.reflect.TypeToken;

import edu.columbia.psl.cc.analysis.StaticTester;
import edu.columbia.psl.cc.config.MIBConfiguration;
import edu.columbia.psl.cc.crawler.NativePackages;
import edu.columbia.psl.cc.datastruct.BytecodeCategory;
import edu.columbia.psl.cc.datastruct.InstPool;
import edu.columbia.psl.cc.pojo.ClassMethodInfo;
import edu.columbia.psl.cc.pojo.GraphGroup;
import edu.columbia.psl.cc.pojo.GraphTemplate;
import edu.columbia.psl.cc.pojo.InstNode;
import edu.columbia.psl.cc.pojo.MethodNode;
import edu.columbia.psl.cc.pojo.OpcodeObj;

public class MethodStackRecorder {
	
	private static Logger logger = Logger.getLogger(MethodStackRecorder.class);
		
	private static TypeToken<GraphTemplate> GRAPH_TOKEN = new TypeToken<GraphTemplate>(){};
	
	public static int CONSTRUCTOR_DEFAULT = -5;
	
	private static String init = "<init>";
	
	private static String clinit = "<clinit>";
	
	private static String defaultPkgId = String.valueOf(NativePackages.defaultId);
		
	private String className;
	
	private String methodName;
	
	private String methodDesc;
	
	private String methodKey;
	
	private String shortMethodKey;
	
	private boolean staticMethod;
	
	private int methodArgSize = 0;
	
	private int methodReturnSize = 0;
		
	private Stack<InstNode> stackSimulator = new Stack<InstNode>();
	
	private InstNode curControlInst;
		
	//Key: local var idx, Val: inst node
	private Map<Integer, InstNode> localVarRecorder = new HashMap<Integer, InstNode>();
	
	private HashMap<String, String> rwFieldRelations = new HashMap<String, String>();
		
	private HashMap<String, InstNode> writeFields = new HashMap<String, InstNode>();
			
	//Record which insts might be affecte by field written by parent method
	private HashSet<String> firstReadLocalVars = new HashSet<String>();
	
	private HashSet<Integer> shouldRecordReadLocalVars = new HashSet<Integer>();
		
	protected String curLabel = null;
	
	public int linenumber = 0;
	
	protected InstPool pool = new InstPool();
		
	private InstNode beforeReturn;
	
	private int threadId = -1;
	
	private int threadMethodId = -1;
	
	public int objId = 0;
	
	private HashMap<String, GraphGroup> calleeCache = new HashMap<String, GraphGroup>();
	
	private boolean stopRecord = false;
	
	public MethodStackRecorder(String className, 
			String methodName, 
			String methodDesc, 
			boolean isStatic, 
			int objId) {
		
		if (TimeController.isOverTime()) {
			this.stopRecord = true;
			return ;
		}
		
		this.className = className;
		this.methodName = methodName;
		this.methodDesc = methodDesc;
				
		this.methodKey = StringUtil.genKey(className, methodName, methodDesc);
		this.shortMethodKey = GlobalRecorder.getGlobalName(this.methodKey);
		Type methodType = Type.getMethodType(this.methodDesc);
		this.methodArgSize = methodType.getArgumentTypes().length;
		if (methodType.getReturnType().getSort() == Type.VOID) {
			this.methodReturnSize = 0;
		} else if (methodType.getReturnType().getSort() == Type.DOUBLE 
				|| methodType.getReturnType().getSort() == Type.LONG) {
			this.methodReturnSize = 2;
		} else {
			this.methodReturnSize = 1;
		}
			
		/*if (!methodType.getReturnType().getDescriptor().equals("V")) {
			this.methodReturnSize = 1;
		}*/
		
		//this.smm = GlobalRecorder.getStaticMethodMiner(this.shortMethodKey);
		//this.threadId = Thread.currentThread().getId();
		
		this.staticMethod = isStatic;		
		this.objId = objId;
		
		ClassInfoCollector.initiateClassMethodInfo(className, 
				methodName, 
				methodDesc, 
				this.staticMethod);
		
		this.threadId = ObjectIdAllocater.getThreadId();
		/*this.threadMethodId = ObjectIdAllocater.getThreadMethodIndex(className, 
				methodName, 
				methodDesc, 
				this.threadId);*/
		this.threadMethodId = ObjectIdAllocater.getThreadMethodIndex(this.threadId);
		
		int start = 0;
		if (!this.staticMethod) {
			//Start from 0
			this.shouldRecordReadLocalVars.add(0);
			start = 1;
		}
		
		for (Type t: methodType.getArgumentTypes()) {
			this.shouldRecordReadLocalVars.add(start);
			if (t.getDescriptor().equals("D") || t.getDescriptor().equals("J")) {
				start += 2;
			} else {
				start += 1;
			}
		}
		
		/*logger.info("Enter " + 
				" " + this.methodKey + 
				" " + this.threadId + 
				" " + this.threadMethodId);*/
		
		//Load possible clinit
		/*if (this.methodName.equals(init)) {
			//Instead of using NEW, let init to attempt loading clinit
			this.checkNGetClInit(this.className);
		}*/
	}
	
	private void stopLocalVar(int localVarId) {
		this.shouldRecordReadLocalVars.remove(localVarId);
	}
	
	private void updateReadLocalVar(InstNode localVarNode) {
		int localVarId = Integer.valueOf(localVarNode.getAddInfo());
		if (this.shouldRecordReadLocalVars.contains(localVarId)) {
			String localVarIdxKey = StringUtil.genIdxKey(localVarNode.getThreadId(), 
					localVarNode.getThreadMethodIdx(), 
					localVarNode.getIdx());
			this.firstReadLocalVars.add(localVarIdxKey);
		}
	}
			
	private void updateCachedMap(InstNode parent, InstNode child, int depType) {
		if (depType == MIBConfiguration.INST_DATA_DEP) {
			parent.increChild(child.getThreadId(), child.getThreadMethodIdx(), child.getIdx(), MIBConfiguration.getInstance().getInstDataWeight());
			child.registerParent(parent.getThreadId(), parent.getThreadMethodIdx(), parent.getIdx(), depType);
		} else if (depType == MIBConfiguration.WRITE_DATA_DEP) {
			//write data dep only needs to be recorded once
			/*String childIdxKey = StringUtil.genIdxKey(child.getFromMethod(), child.getThreadId(), child.getThreadMethodIdx(), child.getIdx());
			if (parent.getChildFreqMap().containsKey(childIdxKey))
				return ;*/
			parent.increChild(child.getThreadId(), child.getThreadMethodIdx(), child.getIdx(), MIBConfiguration.getInstance().getWriteDataWeight());
			child.registerParent(parent.getThreadId(), parent.getThreadMethodIdx(), parent.getIdx(), depType);
		} else if (depType == MIBConfiguration.CONTR_DEP) {
			parent.increChild(child.getThreadId(), child.getThreadMethodIdx(), child.getIdx(), MIBConfiguration.getInstance().getControlWeight());
			child.registerParent(parent.getThreadId(), parent.getThreadMethodIdx(), parent.getIdx(), depType);
		}
	}
	
	private void updateStackSimulator(InstNode fullInst, int addOutput) {
		int outputSize = fullInst.getOp().getOutList().size() + addOutput;
		this.updateStackSimulator(outputSize, fullInst);
	}
	
	private void updateStackSimulator(int times, InstNode fullInst) {
		//System.out.println("Stack push: " + fullInst + " " + times);
		for (int i = 0; i < times; i++) {
			this.stackSimulator.push(fullInst);
		}
	}
	
	private void showStackSimulator() {
		//System.out.println(this.stackSimulator);
		logger.info(this.stackSimulator);
	}
	
	public void updateCurLabel(String curLabel) {
		if (this.stopRecord)
			return ;
		
		this.curLabel = curLabel;
		
		/*if (this.checkLabel) {
			if (this.curControlInst.getOp().getOpcode() != Opcodes.GOTO) {
				logger.error("Control inst not goto: " + this.curControlInst);
			}
			String expectLabel = this.curControlInst.getAddInfo();
			if (!this.curLabel.equals(expectLabel))
				this.curControlInst = null;
			
			this.checkLabel = false;
		}*/
	}
	
	private void updateControlRelation(InstNode fullInst) {
		if (this.curControlInst != null)
			this.updateCachedMap(this.curControlInst, fullInst, MIBConfiguration.CONTR_DEP);
	}
	
	public void checkNGetClInit(String targetClass) {
		/*String cleanClass = StringUtil.cleanPunc(targetClass, ".");
		String targetDumpKey = GlobalRecorder.getLoadedClass(cleanClass);
		if (targetDumpKey == null) {
			logger.info("Current method: " + this.methodKey);
			logger.info("No record for: " + cleanClass);
			logger.info("Current map: " + GlobalRecorder.getLoadedClasses());
			return ;
		}
		
		logger.info(this.methodKey + " is loading " + cleanClass + " clinit");
		this.doLoadParent(targetDumpKey);*/
	}
		
	public void loadParent(String owner, String name, String desc) {
		if (this.stopRecord)
			return ;
		
		String methodKey = StringUtil.genKey(owner, name, desc);
		logger.info("Attempt to load parent/self constructor: " + methodKey);
		
		//String searchKey = StringUtil.genKeyWithId(methodKey, String.valueOf(this.threadId));
		String shortName = GlobalRecorder.getGlobalName(methodKey);
		if (shortName != null) {
			String searchKey = StringUtil.genKeyWithId(GlobalRecorder.getGlobalName(methodKey), String.valueOf(this.threadId));
			logger.info("Search key: " + searchKey);
			this.doLoadParent(searchKey);
		} else {
			logger.info("Null short name in record: " + methodKey);
		}
		
	}
	
	private void doLoadParent(String searchKey) {
		String filePath = "";
		if (MIBConfiguration.getInstance().isTemplateMode()) {
			filePath = MIBConfiguration.getInstance().getTemplateDir() + "/" + searchKey + ".json";
		} else {
			filePath = MIBConfiguration.getInstance().getTestDir() + "/" + searchKey + ".json";
		}
		GraphTemplate parentGraph = TemplateLoader.loadTemplateFile(filePath, GRAPH_TOKEN);
		
		if (parentGraph == null) {
			logger.warn("Load no parent/clinit graph: " + searchKey);
			return ;
		}
		
		InstPool parentPool = parentGraph.getInstPool();
		
		GraphUtil.removeReturnInst(parentGraph.getInstPool());
		GraphUtil.unionInstPools(this.pool, parentPool);
	}
	
	private synchronized InstNode safePop() {
		if (this.stackSimulator.size() > 0) {
			return this.stackSimulator.pop();
		}
		return null;
	}
	
	public void updateObjOnStack(Object obj, int traceBack) {
		//logger.info("Logged obj: " + obj);
		//logger.info("Trace back: " + traceBack);
		//logger.info("Current statck: " + this.stackSimulator);
		if (this.stopRecord)
			return ;
		
		int idx = this.stackSimulator.size() - 1 - traceBack;
		InstNode latestInst = this.stackSimulator.get(idx);
		latestInst.setRelatedObj(obj);
	}
	
	public void handleLdc(int opcode, int instIdx, int times, String addInfo) {
		if (this.stopRecord)
			return ;
		
		//logger.info("Handle ldc: " + opcode + " " + instIdx + " " + addInfo);
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
				this.threadId, 
				this.threadMethodId, 
				instIdx, 
				opcode, 
				addInfo, 
				InstPool.REGULAR);
		fullInst.setLinenumber(this.linenumber);
		//this.updateTime(fullInst);
		
		this.updateControlRelation(fullInst);
		
		this.updateStackSimulator(times, fullInst);
		//this.showStackSimulator();
	}
	
	public void handleField(int opcode, int instIdx, String owner, String name, String desc) {
		if (this.stopRecord)
			return ;
		
		//logger.info("Handle field: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		OpcodeObj oo = BytecodeCategory.getOpcodeObj(opcode);
		int opcat = oo.getCatId();
		int typeSort = Type.getType(desc).getSort();
		
		int objId = 0;
		if (opcode == Opcodes.GETFIELD) {
			objId = ObjectIdAllocater.parseObjId(this.stackSimulator.peek().getRelatedObj());
			this.stackSimulator.peek().removeRelatedObj();
		} else if (opcode == Opcodes.PUTFIELD) {
			if (typeSort == Type.LONG || typeSort == Type.DOUBLE) {
				objId = ObjectIdAllocater.parseObjId(this.stackSimulator.get(this.stackSimulator.size() - 3).getRelatedObj());
				this.stackSimulator.get(this.stackSimulator.size() - 3).removeRelatedObj();
			} else {
				objId = ObjectIdAllocater.parseObjId(this.stackSimulator.get(this.stackSimulator.size() - 2).getRelatedObj());
				this.stackSimulator.get(this.stackSimulator.size() - 2).removeRelatedObj();
			}
		}
		
		//Search the real owner of the field
		Class<?> targetClass = ClassInfoCollector.retrieveCorrectClassByField(owner, name);
		//logger.info("Class owner of field with objId: " + targetClass + " " + owner + " " + name);
		
		/*if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
			//JVM will load the owner, not the exact class
			if (!owner.equals(this.className))
				this.checkNGetClInit(targetClass.getName());
		}*/
		
		String fieldKey = targetClass.getName() + "." + name + "." + desc;
		
		if (objId > 0) {
			//fieldKey += objId;
			fieldKey = fieldKey + ":" + objId;
		}
		
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
				this.threadId, 
				this.threadMethodId, 
				instIdx, 
				opcode, 
				fieldKey, 
				InstPool.FIELD);
		fullInst.setLinenumber(this.linenumber);
		this.updateControlRelation(fullInst);
		
		if (MIBConfiguration.getInstance().isFieldTrack()) {
			if (BytecodeCategory.readFieldCategory().contains(opcat) && objId >= 0) {
				//Add info for field: owner + name + desc + objId
				InstNode writeInst = GlobalRecorder.getWriteField(fieldKey);
				
				if (writeInst != null) {
					GlobalRecorder.registerRWFieldHistory(writeInst, fullInst);
					String writeIdx = FieldRecorder.toIndex(writeInst);
					String readIdx = FieldRecorder.toIndex(fullInst);
					this.rwFieldRelations.put(writeIdx, readIdx);
				} else {
					//Some inst and static fields are possible NOT to be initialized
					logger.warn("Field access warning: cannot find wite inst for " + fullInst);
				}
			} else if (BytecodeCategory.writeFieldCategory().contains(opcat) && objId >= 0) {
				GlobalRecorder.registerWriteField(fieldKey, fullInst);
				this.writeFields.put(fieldKey, fullInst);
			} else {
				logger.error("Error: fail to retrieve object ID " + opcode + " " + instIdx + " " + owner + " " + desc);
			}
		}
		
		int addInput = 0, addOutput = 0;
		if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
			if (typeSort == Type.DOUBLE || typeSort == Type.LONG) {
				addInput++;
			}
		} else if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
			if (typeSort == Type.DOUBLE || typeSort == Type.LONG) {
				addOutput++;
			}
		}
		
		int inputSize = oo.getInList().size() + addInput;
		if (inputSize > 0) {
			InstNode curInst = null;
			for (int i = 0; i < inputSize; i++) {
				//Should not return null here
				InstNode tmpInst = this.safePop();
				if (!tmpInst.equals(curInst)) {
					this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
				}
				curInst = tmpInst;
			}
		}
		this.updateStackSimulator(fullInst, addOutput);
		//this.showStackSimulator();
	}
	
	public void handleOpcode(int opcode, int instIdx, String addInfo) {
		if (this.stopRecord)
			return ;
		
		//logger.info("Handle opcode: " + opcode + " " + instIdx + " " + addInfo);
		OpcodeObj oo = BytecodeCategory.getOpcodeObj(opcode);
		int opcat = oo.getCatId();
		
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
				this.threadId, 
				this.threadMethodId, 
				instIdx, 
				opcode, 
				addInfo, 
				InstPool.REGULAR);
		fullInst.setLinenumber(this.linenumber);
		//this.updateTime(fullInst);
		this.updateControlRelation(fullInst);
		
		int inputSize = oo.getInList().size();
		if (inputSize > 0) {
			for (int i = 0; i < inputSize; i++) {
				//Should not return null here
				InstNode tmpInst = this.safePop();
				this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			}
		}
		this.updateStackSimulator(fullInst, 0);
		//this.showStackSimulator();
		
		if (BytecodeCategory.controlCategory().contains(opcat) 
				|| opcode == Opcodes.TABLESWITCH 
				|| opcode == Opcodes.LOOKUPSWITCH) {
			this.curControlInst = fullInst;
			
			/*if (this.curControlInst.getOp().getOpcode() == Opcodes.GOTO)
				this.checkLabel = true;*/
		}
	}
	
	/**
	 * localVarIdx is not necessarily a local var
	 * @param opcode
	 * @param localVarIdx
	 */
	public void handleOpcode(int opcode, int instIdx, int localVarIdx) {
		if (this.stopRecord)
			return ;
		
		//logger.info("Handle opcode: " + opcode + " " + localVarIdx);
		
		//Don't record return inst, or need to remove it later
		if (BytecodeCategory.returnOps().contains(opcode)) {
			OpcodeObj returnOp = BytecodeCategory.getOpcodeObj(opcode);
			int inputSize = returnOp.getInList().size();
			
			if (inputSize > 0) {
				InstNode tmpInst = this.safePop();
				this.beforeReturn = tmpInst;
				if (inputSize == 2) {
					this.safePop();
				}
			}
			return ;
		}
		
		InstNode fullInst = null;
		if (localVarIdx >= 0) {
			fullInst = this.pool.searchAndGet(this.methodKey, 
					this.threadId, 
					this.threadMethodId, 
					instIdx, 
					opcode, 
					String.valueOf(localVarIdx), 
					InstPool.REGULAR);
		} else {
			fullInst = this.pool.searchAndGet(this.methodKey, 
					this.threadId, 
					this.threadMethodId, 
					instIdx, 
					opcode, 
					"", 
					InstPool.REGULAR);
		}
		fullInst.setLinenumber(this.linenumber);
		//this.updateTime(fullInst);
		
		int opcat = fullInst.getOp().getCatId();
		
		InstNode lastInst = null;
		if (!stackSimulator.isEmpty()) {
			lastInst = stackSimulator.peek();
		}
		
		if (!BytecodeCategory.dupCategory().contains(opcat)) {
			//Dup inst will be replaced later. No need to add any dep
			this.updateControlRelation(fullInst);
		}
		
		//The store instruction will be the sink. The inst on the stack will be source
		boolean hasUpdate = false;
		if (BytecodeCategory.writeCategory().contains(opcat)) {
			if (lastInst != null) {
				if (localVarIdx >= 0) {
					this.localVarRecorder.put(localVarIdx, fullInst);
				}
				
				this.updateCachedMap(lastInst, fullInst, MIBConfiguration.INST_DATA_DEP);
				for (int i = 0; i < fullInst.getOp().getInList().size(); i++)
					this.safePop();
			}
			this.stopLocalVar(localVarIdx);
		} else if (opcode == Opcodes.IINC) {
			InstNode parentInst = this.localVarRecorder.get(localVarIdx);
			if (parentInst != null)
				this.updateCachedMap(parentInst, fullInst, MIBConfiguration.WRITE_DATA_DEP);
			
			this.localVarRecorder.put(localVarIdx, fullInst);
			this.updateReadLocalVar(fullInst);
			this.stopLocalVar(localVarIdx);
		} else if (BytecodeCategory.readCategory().contains(opcat)) {
			//Search local var recorder;
			InstNode parentInst = this.localVarRecorder.get(localVarIdx);
			if (parentInst != null) {
				this.updateCachedMap(parentInst, fullInst, MIBConfiguration.WRITE_DATA_DEP);
			}
			
			this.updateReadLocalVar(fullInst);
		} else if (BytecodeCategory.dupCategory().contains(opcat)) {
			this.handleDup(opcode);
			//dup should not have any dep, no need to parentRemove
			this.pool.remove(fullInst);
			hasUpdate = true;
		} else {			
			int inputSize = fullInst.getOp().getInList().size();
			InstNode lastTmp = null;
			if (inputSize > 0) {
				for (int i = 0; i < inputSize; i++) {
					//Should not return null here
					InstNode tmpInst = this.safePop();
					if (!tmpInst.equals(lastTmp)) {
						this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
						
						/*if (BytecodeCategory.returnOps().contains(fullInst.getOp().getOpcode())) {
							this.beforeReturn = tmpInst;
						}*/
					}
					
					lastTmp = tmpInst;
				}
			}
		}
		
		if (!hasUpdate) 
			this.updateStackSimulator(fullInst, 0);
		//this.showStackSimulator();
	}
	
	public void handleMultiNewArray(String desc, int dim, int instIdx) {
		if (this.stopRecord)
			return ;
		
		//logger.info("Handle MultiNewArray: " + desc + " " + dim + " " + instIdx);
		String addInfo = desc + " " + dim;
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
				this.threadId, 
				this.threadMethodId, 
				instIdx, 
				Opcodes.MULTIANEWARRAY, 
				addInfo, 
				InstPool.REGULAR);
		fullInst.setLinenumber(this.linenumber);
		//this.updateTime(fullInst);
		
		this.updateControlRelation(fullInst);
		
		for (int i = 0; i < dim; i++) {
			InstNode tmpInst = this.safePop();
			this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
		}
		this.updateStackSimulator(fullInst, 0);
		//this.showStackSimulator();
	}
	
	private void handleRawMethod(int opcode, int instIdx, int linenum, String owner, String name, String desc, InstNode fullInst) {
		//logger.info("Handling uninstrumented/undersize method: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		fullInst.setLinenumber(linenum);
		//this.updateTime(fullInst);
		this.updateControlRelation(fullInst);
		
		Type methodType = Type.getMethodType(desc);
		Type[] args = methodType.getArgumentTypes();
		
		for (int i = args.length - 1; i >= 0; i--) {
			Type t = args[i];
			if (t.getDescriptor().equals("D") || t.getDescriptor().equals("J")) {
				this.safePop();
				InstNode tmpInst = this.safePop();
				this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			} else {
				InstNode tmpInst = this.safePop();
				this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			}
		}
		
		if (!BytecodeCategory.staticMethodOps().contains(opcode)) {
			InstNode objRef = this.safePop();
			this.updateCachedMap(objRef, fullInst, MIBConfiguration.INST_DATA_DEP);
		}
		
		String returnType = methodType.getReturnType().getDescriptor();
		if (!returnType.equals("V")) {
			if (returnType.equals("D") || returnType.equals("J")) {
				this.updateStackSimulator(2, fullInst);
			} else {
				this.updateStackSimulator(1, fullInst);
			}
		}
		//this.showStackSimulator();
	}
	
	public void handleMethod(int opcode, int instIdx, int linenum, String owner, String name, String desc) {
		if (this.stopRecord)
			return ;
		
		//long startTime = System.nanoTime();
		//logger.info("Handle method: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		
		ClassMethodInfo cmi = ClassInfoCollector.retrieveClassMethodInfo(owner, name, desc, opcode);
		int argSize = cmi.argSize;
		Type[] args = cmi.args;
		Type rType = cmi.returnType;
		int endIdx = cmi.endIdx;
		
		try {
			String curMethodKey = StringUtil.genKey(owner, name, desc);
			if (TimeController.isOverTime()) {
				curMethodKey = StringUtil.genKeyWithId(curMethodKey, defaultPkgId);
				InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
						this.threadId, 
						this.threadMethodId, 
						instIdx, 
						opcode, 
						curMethodKey, 
						InstPool.REGULAR);
				this.handleRawMethod(opcode, instIdx, linenum, owner, name, desc, fullInst);
				return ;
			} else {
				//The case for class part is only for debugging
				Class correctClass;
				if (BytecodeCategory.staticMethodOps().contains(opcode)) {
					correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, false);
				} else {
					InstNode relatedInst = this.stackSimulator.get(stackSimulator.size() - argSize - 1);
					Object objOnStack = relatedInst.getRelatedObj();
					
					if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
						//constructor
						correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, true);
					} else if (opcode == Opcodes.INVOKESPECIAL && owner.equals(this.className)) {
						//private method
						correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, true);
					} else if (opcode == Opcodes.INVOKESPECIAL) {
						//super method, may be in grand parents
						correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, false);
					} else {
						//logger.info("Retrieve method by class name: " + name + objOnStack.getClass().getName());
						String internalName = Type.getInternalName(objOnStack.getClass());
						correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(internalName, name, desc, false);
					}
				}
				
				String realMethodKey = StringUtil.genKey(correctClass.getName(), name, desc);
				if (Type.getType(owner).getSort() == Type.ARRAY 
						|| !StringUtil.shouldIncludeClass(correctClass.getName()) 
						|| !StringUtil.shouldIncludeMethod(name, desc)
						|| GlobalRecorder.checkUndersizedMethod(GlobalRecorder.getGlobalName(realMethodKey)) 
						|| GlobalRecorder.checkUntransformedClass(correctClass.getName())) {
					String pkgName = StringUtil.extractPkg(correctClass.getName());
					String npId = String.valueOf(GlobalRecorder.getNativePackageId(pkgName));
					curMethodKey = StringUtil.genKeyWithId(curMethodKey, npId);
					InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
							this.threadId, 
							this.threadMethodId, 
							instIdx, 
							opcode, 
							curMethodKey, 
							InstPool.REGULAR);
					this.handleRawMethod(opcode, instIdx, linenum, owner, name, desc, fullInst);
					return ;
				}
				
				//logger.info("Before retrieve: " + owner + " " + name + " " + desc);
				//GlobalRecorder.checkLatestGraphs();
				GraphTemplate childGraph = GlobalRecorder.getLatestGraph(this.threadId);
				if (childGraph == null) {
					logger.error("No child graph can be retrieved: " + realMethodKey);
					//Add default np ID
					curMethodKey = StringUtil.genKeyWithId(curMethodKey, defaultPkgId);
					InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
							this.threadId, 
							this.threadMethodId, 
							instIdx, 
							opcode, 
							curMethodKey, 
							InstPool.REGULAR);
					this.handleRawMethod(opcode, instIdx, linenum, owner, name, desc, fullInst);
					return ;
				} else if (!childGraph.getMethodName().equals(name) || !childGraph.getMethodDesc().equals(desc)) {
					logger.error("Incompatible graph: " + childGraph.getMethodKey());
					logger.error("Wanted: " + correctClass.getName() + " " + name + " " + desc);
					//Add default np ID
					curMethodKey = StringUtil.genKeyWithId(curMethodKey, defaultPkgId);
					InstNode fullInst = this.pool.searchAndGet(this.methodKey, 
							this.threadId, 
							this.threadMethodId, 
							instIdx, 
							opcode, 
							curMethodKey, 
							InstPool.REGULAR);
					this.handleRawMethod(opcode, instIdx, linenum, owner, name, desc, fullInst);
					GlobalRecorder.recoverLatestGraph(childGraph);
					//System.out.println("Recorder time: " + (System.nanoTime() - startTime));
					return ;
				} else {
					//logger.info("Child graph: " + childGraph.getMethodName());
					//logger.info("Correct class: " + correctClass.getName());
					MethodNode fullInst = (MethodNode)this.pool.searchAndGet(this.methodKey, 
							this.threadId, 
							this.threadMethodId, 
							instIdx, 
							opcode, 
							curMethodKey, 
							InstPool.METHOD);
					fullInst.setLinenumber(linenum);
					//this.updateTime(fullInst);
					this.updateControlRelation(fullInst);
					
					int objId = -1;
					if (BytecodeCategory.staticMethodOps().contains(opcode)) {
						objId = 0;
					} else {
						InstNode relatedInst = this.stackSimulator.get(stackSimulator.size() - argSize - 1);
						Object objOnStack = relatedInst.getRelatedObj();
						//methodId = ObjectIdAllocater.parseObjId(objOnStack);
						objId = ObjectIdAllocater.parseObjId(objOnStack);
						//relatedInst.removeRelatedObj();
					}
					
					if (childGraph.getObjId() == this.objId 
							&& childGraph.getShortMethodKey().equals(this.shortMethodKey)) {
						//logger.info("Recursive object: " + this.objId + " " + this.shortMethodKey);
						GlobalRecorder.registerRecursiveMethod(childGraph.getShortMethodKey());
					}
										
					fullInst.registerCallee(childGraph);
					//this.latestWriteFieldRecorder.putAll(childGraph.getLatestWriteFields());
					
					if (args.length > 0) {
						for (int i = args.length - 1; i >= 0 ;i--) {
							Type t = args[i];
							InstNode targetNode = null;
							if (t.getDescriptor().equals("D") || t.getDescriptor().equals("J")) {
								this.safePop();
								targetNode = this.safePop();
								
								endIdx -= 1;
								//parentFromCaller.put(endIdx, targetNode);
								this.updateCachedMap(targetNode, fullInst, MIBConfiguration.INST_DATA_DEP);
								fullInst.registerParentReplay(endIdx, targetNode);
								endIdx -= 1;
							} else {
								targetNode = this.safePop();
								//parentFromCaller.put(endIdx, targetNode);
								this.updateCachedMap(targetNode, fullInst, MIBConfiguration.INST_DATA_DEP);
								fullInst.registerParentReplay(endIdx, targetNode);
								
								endIdx -= 1;
							}
						}
					}
					
					if (!BytecodeCategory.staticMethodOps().contains(opcode)) {
						//loadNode can be anyload that load an object
						InstNode loadNode = this.safePop();
						//parentFromCaller.put(0, loadNode);
						this.updateCachedMap(loadNode, fullInst, MIBConfiguration.INST_DATA_DEP);
						fullInst.registerParentReplay(0, loadNode);
					}
					
					
					String returnType = rType.getDescriptor();
					if (!returnType.equals("V")) {
						//InstNode lastSecond = childGraph.getLastBeforeReturn();
						if (returnType.equals("D") || returnType.equals("J")) {
							this.updateStackSimulator(2, fullInst);
						} else {
							this.updateStackSimulator(1, fullInst);
						}
					}
					
					/*if (!similar) {
						this.updateVertexEdgeNum(childGraph);
					}*/
				}
			}
		} catch (Exception ex) {
			logger.error("Exception: " + this.methodName + " " + this.threadId + " " + this.threadMethodId, ex);
		}
	}
	
	public void handleDup(int opcode) {
		if (this.stopRecord)
			return ;
		
		InstNode dupInst = null;
		InstNode dupInst2 = null;
		Stack<InstNode> stackBuf;
		switch (opcode) {
			case 89:
				dupInst = this.stackSimulator.peek();
				this.stackSimulator.push(dupInst);
				break ;
			case 90:
				dupInst = this.stackSimulator.peek();
				stackBuf = new Stack<InstNode>();
				for (int i = 0; i < 2; i++) {
					stackBuf.push(this.safePop());
				}
				
				this.stackSimulator.push(dupInst);
				while(!stackBuf.isEmpty()) {
					this.stackSimulator.push(stackBuf.pop());
				}
				break ;
			case 91:
				dupInst = this.stackSimulator.peek();
				stackBuf = new Stack<InstNode>();
				for (int i = 0; i < 3; i++) {
					stackBuf.push(this.safePop());
				}
				
				this.stackSimulator.push(dupInst);
				//Should only push three times
				while (!stackBuf.isEmpty()) {
					this.stackSimulator.push(stackBuf.pop());
				}
				break ;
			case 92:
				dupInst = this.stackSimulator.get(this.stackSimulator.size() - 1);
	 			dupInst2 = this.stackSimulator.get(this.stackSimulator.size() - 2);
	 			
	 			this.stackSimulator.push(dupInst2);
	 			this.stackSimulator.push(dupInst);
	 			break ;
			case 93:
				dupInst = this.stackSimulator.get(this.stackSimulator.size() - 1);
	 			dupInst2 = this.stackSimulator.get(this.stackSimulator.size() - 2);
	 			stackBuf = new Stack<InstNode>();
	 			for (int i = 0; i < 3; i++) {
	 				stackBuf.push(this.safePop());
	 			}
	 			
	 			this.stackSimulator.push(dupInst2);
	 			this.stackSimulator.push(dupInst);
	 			while (!stackBuf.isEmpty()) {
	 				this.stackSimulator.push(stackBuf.pop());
	 			}
	 			break ;
			case 94:
				dupInst = this.stackSimulator.get(this.stackSimulator.size() - 1);
	 			dupInst2 = this.stackSimulator.get(this.stackSimulator.size() - 2);
	 			stackBuf = new Stack<InstNode>();
	 			for (int i =0 ; i < 4; i++) {
	 				stackBuf.push(this.safePop());
	 			}
	 			
	 			this.stackSimulator.push(dupInst2);
	 			this.stackSimulator.push(dupInst);
	 			while (!stackBuf.isEmpty()) {
	 				this.stackSimulator.push(stackBuf.pop());
	 			}
	 			break ;
			case 95:
				dupInst = this.stackSimulator.get(this.stackSimulator.size() - 1);
				dupInst2 = this.stackSimulator.get(this.stackSimulator.size() - 2);
				this.stackSimulator.push(dupInst);
				this.stackSimulator.push(dupInst2);
				break ;
		}
	}
	
	public void dumpGraph() {
		if (this.stopRecord)
			return ;
		
		if (GlobalRecorder.checkUndersizedMethod(this.shortMethodKey)) {
			logger.info("Leave " + " undersized" +
					" " + this.methodKey + 
					" " + this.threadId + 
					" " + this.threadMethodId);
			return ;
		}
		
		GraphTemplate gt = new GraphTemplate();
		
		gt.setMethodKey(this.methodKey);
		gt.setMethodName(this.methodName);
		gt.setMethodDesc(this.methodDesc);
		gt.setShortMethodKey(this.shortMethodKey);
		gt.setThreadId(this.threadId);
		gt.setThreadMethodId(this.threadMethodId);
		gt.setObjId(this.objId);
		gt.setMethodArgSize(this.methodArgSize);
		gt.setMethodReturnSize(this.methodReturnSize);
		gt.setStaticMethod(this.staticMethod);
		gt.setFirstReadLocalVars(this.firstReadLocalVars);
		
		if (this.beforeReturn != null) {
			gt.setLastBeforeReturn(this.beforeReturn);
			//logger.info("Before return inst: " + this.beforeReturn);
		}
		
		HashMap<String, GraphTemplate> calleeRequired = new HashMap<String, GraphTemplate>();
		Iterator<InstNode> instIterator = this.pool.iterator();
		int edgeNum = 0, vertexNum = 0;
		double[] dist = new double[256];
		while (instIterator.hasNext()) {
			InstNode curInst = instIterator.next();
			curInst.removeRelatedObj();
			
			int childNum = curInst.getChildFreqMap().size();
			if (curInst instanceof MethodNode) {
				MethodNode mn = (MethodNode) curInst;
				//GraphTemplate repCallee = MethodNode.extractCallee(mn.getCallees(), mn.getMaxCalleeFreq());
				HashMap<GraphTemplate, Double> repCallees = MethodNode.extractCallee(mn.getCallees(), mn.getMaxCalleeFreq());
				
				int instParentNum = mn.getInstDataParentList().size();
				int controlParentNum = mn.getControlParentList().size();
				for (GraphTemplate repCallee: repCallees.keySet()) {
					String repKey = StringUtil.genThreadWithMethodIdx(repCallee.getThreadId(), repCallee.getThreadMethodId());
					double normFreq = repCallees.get(repCallee);
					mn.registerDomCalleeIdx(repKey, normFreq, repCallee.getLastBeforeReturn());
					calleeRequired.put(repKey, repCallee);
					
					/*if (repCallee.getLastBeforeReturn() != null) {
						mn.registerChildReplace(repCallee.getLastBeforeReturn());
					}*/
					
					vertexNum += (repCallee.getVertexNum());
					int firstReadNum = repCallee.getFirstReadLocalVars().size();
					edgeNum = edgeNum 
							+ repCallee.getEdgeNum() 
							+ firstReadNum 
							+ firstReadNum * controlParentNum
							+ childNum;
					mn.clearCallees();
					
					StaticTester.sumDistribution(dist, repCallee.getDist());
				}
				vertexNum--;
				edgeNum = edgeNum - instParentNum - controlParentNum;
			} else {
				dist[curInst.getOp().getOpcode()] += 1;
				edgeNum += childNum;
			}
		}
		vertexNum += this.pool.size();
		
		gt.setEdgeNum(edgeNum);
		gt.setVertexNum(vertexNum);
		gt.calleeRequired = calleeRequired;
		
		//Accumulate write fields and field rw relations from callees
		HashMap<String, InstNode> totalWriteFields = new HashMap<String, InstNode>();
		HashMap<String, String> totalRwFieldRelations = new HashMap<String, String>();
		for (GraphTemplate child: calleeRequired.values()) {
			totalWriteFields.putAll(child.writeFields);
			totalRwFieldRelations.putAll(child.fieldRelations);
		}
		totalWriteFields.putAll(this.writeFields);
		totalRwFieldRelations.putAll(this.rwFieldRelations);
		
		gt.writeFields = totalWriteFields;
		gt.fieldRelations = totalRwFieldRelations;
		
		gt.setInstPool(this.pool);
		gt.setDist(dist);
		
		//logger.info("Total edge count: " + gt.getEdgeNum());
		//logger.info("Total vertex count: " + gt.getVertexNum());
		
		//String dumpKey = StringUtil.genKeyWithId(this.methodKey, String.valueOf(this.threadId));
		String dumpKey = StringUtil.genKeyWithId(this.shortMethodKey, String.valueOf(this.threadId));
		boolean registerLatest = (!this.methodName.equals("<clinit>"));
		GlobalRecorder.registerGraph(dumpKey, gt, registerLatest);
		
		//Debugging, check graph group
		if (MIBConfiguration.getInstance().isDebug()) {
			this.serializeGraphs(dumpKey, gt);
			
			logger.info("Graph groups:");
			for (String searchKey: this.calleeCache.keySet()) {
				logger.info("Group name: " + searchKey);
				GraphGroup gGroup = this.calleeCache.get(searchKey);
				logger.info(gGroup.keySet());
			}
		}
		
		//gt.calleeCache = this.calleeCache;
		//this.showStackSimulator();
		/*logger.info("Leave " + 
				" " + this.methodKey + 
				" " + this.threadId + 
				" " + this.threadMethodId);*/
	}
	
	private void serializeGraphs(String dumpKey, GraphTemplate gt) {
		TypeToken<GraphTemplate> typeToken = new TypeToken<GraphTemplate>(){};
		if (MIBConfiguration.getInstance().isTemplateMode()) {
			GsonManager.cacheGraph(dumpKey, 0, false);
			GsonManager.writeJsonGeneric(gt, dumpKey, typeToken, MIBConfiguration.TEMPLATE_DIR);
		} else {
			GsonManager.cacheGraph(dumpKey, 1, false);
			GsonManager.writeJsonGeneric(gt, dumpKey, typeToken, MIBConfiguration.TEST_DIR);
		}
		//GsonManager.writePath(dumpKey, this.path);
	}
}
