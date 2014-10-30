package edu.columbia.psl.cc.util;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import com.google.gson.reflect.TypeToken;

import edu.columbia.psl.cc.config.MIBConfiguration;
import edu.columbia.psl.cc.datastruct.BytecodeCategory;
import edu.columbia.psl.cc.datastruct.InstPool;
import edu.columbia.psl.cc.pojo.ExtObj;
import edu.columbia.psl.cc.pojo.GraphTemplate;
import edu.columbia.psl.cc.pojo.InstNode;
import edu.columbia.psl.cc.pojo.OpcodeObj;
import edu.columbia.psl.cc.pojo.StaticRep;
import edu.columbia.psl.cc.pojo.SurrogateInst;
import edu.columbia.psl.cc.premain.MIBDriver;

public class MethodStackRecorder {
		
	private static TypeToken<GraphTemplate> graphToken = new TypeToken<GraphTemplate>(){};
	
	private AtomicInteger curTime = new AtomicInteger();
		
	private String className;
	
	private String methodName;
	
	private String methodDesc;
	
	private String methodKey;
	
	private boolean staticMethod;
	
	private int methodArgSize = 0;
	
	private int methodReturnSize = 0;
		
	private Stack<InstNode> stackSimulator = new Stack<InstNode>();
	
	private InstNode curControlInst = null;
	
	//Key: local var idx, Val: inst node
	private Map<Integer, InstNode> localVarRecorder = new HashMap<Integer, InstNode>();
	
	//Key: field name, Val: inst node
	private Map<String, InstNode> fieldRecorder = new HashMap<String, InstNode>();
	
	//Record which insts might be affected by input params
	private HashSet<InstNode> firstReadFields = new HashSet<InstNode>();
	
	//Record which insts might be affecte by field written by parent method
	private HashSet<InstNode> firstReadLocalVars = new HashSet<InstNode>();
	
	private HashSet<Integer> shouldRecordReadLocalVars = new HashSet<Integer>();
	
	private List<InstNode> path = new ArrayList<InstNode>();
	
	//public Object objOnStack = null;
	
	public String curLabel = null;
	
	private InstPool pool = new InstPool();
	
	private int id = -1;
	
	private int maxTime = -1;
	
	public MethodStackRecorder(String className, 
			String methodName, 
			String methodDesc, 
			Object obj) {
		this.className = className;
		this.methodName = methodName;
		this.methodDesc = methodDesc;
				
		this.methodKey = StringUtil.genKey(className, methodName, methodDesc);
		Type methodType = Type.getMethodType(this.methodDesc);
		this.methodArgSize = methodType.getArgumentTypes().length;
		if (!methodType.getReturnType().getDescriptor().equals("V")) {
			this.methodReturnSize = 1;
		}
		
		if (obj == null) {
			this.id = 0;
			this.staticMethod = true;
		} else {
			this.id = ObjectIdAllocater.parseObjId(obj);
		}
		System.out.println("Check method key: " + this.methodKey);
		//System.out.println("Check object: " + obj);
		System.out.println("Check id: " + this.id);
		
		int count = 0, start = 0;
		if (!this.staticMethod) {
			//Start from 0
			this.shouldRecordReadLocalVars.add(0);
			start = 1;
		}
		
		while (count < methodArgSize) {
			this.shouldRecordReadLocalVars.add(start);
			start++;
			count++;
		}
	}
	
	private int getCurTime() {
		return this.curTime.getAndIncrement();
	}
	
	private void stopLocalVar(int localVarId) {
		this.shouldRecordReadLocalVars.remove(localVarId);
	}
	
	private void updateReadLocalVar(InstNode localVarNode) {
		int localVarId = Integer.valueOf(localVarNode.getAddInfo());
		if (this.shouldRecordReadLocalVars.contains(localVarId)) {
			this.firstReadLocalVars.add(localVarNode);
		}
	}
	
	private void updateReadField(InstNode fieldNode) {		
		this.firstReadFields.add(fieldNode);
	}
	
	private void removeReadFields(String field) {
		Iterator<InstNode> frIterator = this.firstReadFields.iterator();
		while (frIterator.hasNext()) {
			InstNode inst = frIterator.next();
			
			if (inst.getAddInfo().equals(field))
				frIterator.remove();
		}
	}
		
	private void updatePath(InstNode fullInst) {
		this.path.add(fullInst);
	}
	
	private void updateTime(InstNode fullInst) {
		int curTime = this.getCurTime();
		if (fullInst.getStartTime() < 0) {
			fullInst.setStartTime(curTime);
			fullInst.setUpdateTime(curTime);	
		} else {
			fullInst.setUpdateTime(curTime);
		}
		
		if (curTime > this.maxTime) {
			maxTime = curTime;
		}
 	}
	
	private void updateCachedMap(InstNode parent, InstNode child, int depType) {		
		if (depType == MIBConfiguration.INST_DATA_DEP) {
			parent.increChild(child.getFromMethod(), child.getMethodId(), child.getIdx(), MIBConfiguration.getInstance().getInstDataWeight());
			child.registerParent(parent.getFromMethod(), parent.getMethodId(), parent.getIdx(), depType);
		} else if (depType == MIBConfiguration.WRITE_DATA_DEP) {
			//write data dep only needs to be recorded once
			String childIdxKey = StringUtil.genIdxKey(child.getFromMethod(), child.getMethodId(), child.getIdx());
			if (parent.getChildFreqMap().containsKey(childIdxKey))
				return ;
			
			parent.increChild(child.getFromMethod(), child.getMethodId(), child.getIdx(), MIBConfiguration.getInstance().getWriteDataWeight());
			child.registerParent(parent.getFromMethod(), parent.getMethodId(), parent.getIdx(), depType);
			
			if (child.getSurrogateInsts().size() > 0) {
				for (SurrogateInst surNode: child.getSurrogateInsts()) {
					if (surNode.equals(child))
						continue ;
					
					parent.increChild(surNode.getFromMethod(), surNode.getMethodId(), surNode.getIdx(), MIBConfiguration.getInstance().getWriteDataWeight());
					surNode.registerParent(parent.getFromMethod(), parent.getMethodId(), parent.getIdx(), depType);
				}
			}
		} else if (depType == MIBConfiguration.CONTR_DEP) {
			parent.increChild(child.getFromMethod(), child.getMethodId(), child.getIdx(), MIBConfiguration.getInstance().getControlWeight());
			child.registerParent(parent.getFromMethod(), parent.getMethodId(), parent.getIdx(), depType);
			
			if (child.getSurrogateInsts().size() > 0) {
				for (SurrogateInst surNode: child.getSurrogateInsts()) {
					if (surNode.equals(child))
						continue ;
					
					parent.increChild(surNode.getFromMethod(), surNode.getMethodId(), surNode.getIdx(), MIBConfiguration.getInstance().getControlWeight());
					surNode.registerParent(parent.getFromMethod(), parent.getMethodId(), parent.getIdx(), depType);
				}
			}
		}
	}
	
	private synchronized InstNode safePop() {
		if (this.stackSimulator.size() > 0) {
			return this.stackSimulator.pop();
		}
		return null;
	}
	
	private synchronized void updateControlInst(InstNode fullInst) {
		this.curControlInst = fullInst;
		//this.curControlInsts.add(fullInst);
	}
	
	public void updateObjOnStack(Object obj, int traceBack) {
		int idx = this.stackSimulator.size() - 1 - traceBack;
		InstNode latestInst = this.stackSimulator.get(idx);
		//InstNode latestInst = this.stackSimulator.peek();
		latestInst.setRelatedObj(obj);
		System.out.println("Inst add obj: " + latestInst);
	}
	
	public void handleLdc(int opcode, int instIdx, int times, String addInfo) {
		System.out.println("Handling now: " + opcode + " " + instIdx + " " + addInfo);
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, addInfo);
		this.updateTime(fullInst);
		
		this.updateControlRelation(fullInst);
		this.updatePath(fullInst);
		
		this.updateStackSimulator(times, fullInst);
		this.showStackSimulator();
	}
	
	public void handleField(int opcode, int instIdx, String owner, String name, String desc) {
		System.out.println("Handling now: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		OpcodeObj oo = BytecodeCategory.getOpcodeObj(opcode);
		int opcat = oo.getCatId();
		int typeSort = Type.getType(desc).getSort();
		
		//int objId = parseObjId(this.objOnStack);
		int objId = 0;
		
		if (opcode == Opcodes.GETFIELD) {
			objId = ObjectIdAllocater.parseObjId(this.stackSimulator.peek().getRelatedObj());
		} else if (opcode == Opcodes.PUTFIELD) {
			if (typeSort == Opcodes.LONG || typeSort == Opcodes.DOUBLE) {
				objId = ObjectIdAllocater.parseObjId(this.stackSimulator.get(this.stackSimulator.size() - 3).getRelatedObj());
			} else {
				objId = ObjectIdAllocater.parseObjId(this.stackSimulator.get(this.stackSimulator.size() - 2).getRelatedObj());
			}
		}
		
		//Search the real owner of the field
		Class<?> targetClass = ClassInfoCollector.retrieveCorrectClassByField(owner, name);
		System.out.println("Target class: " + targetClass + " " + " " + desc);
		System.out.println("Object id: " + objId);
		String fieldKey = targetClass.getName() + "." + name + "." + desc;
		
		if (objId > 0) {
			fieldKey += objId;
		}
		
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, fieldKey);
		this.updateTime(fullInst);
		this.updateControlRelation(fullInst);
		this.updatePath(fullInst);
		
		if (BytecodeCategory.readFieldCategory().contains(opcat)) {
			//Add info for field: owner + name + desc + objId
			//Only record static or the instrumented object
			if (opcode == Opcodes.GETSTATIC || objId > 0) {
				InstNode parent = this.fieldRecorder.get(fieldKey);
				if (parent != null)
					this.updateCachedMap(parent, fullInst, MIBConfiguration.WRITE_DATA_DEP);
				else
					this.updateReadField(fullInst);
			}
		} else if (BytecodeCategory.writeFieldCategory().contains(opcat)) {
			if (opcode == Opcodes.PUTSTATIC || objId > 0) {
				this.fieldRecorder.put(fieldKey, fullInst);
				this.removeReadFields(fieldKey);
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
			for (int i = 0; i < inputSize; i++) {
				//Should not return null here
				InstNode tmpInst = this.safePop();
				this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			}
		}
		this.updateStackSimulator(fullInst, addOutput);
		this.showStackSimulator();
	}
	
	public void handleOpcode(int opcode, int instIdx, String addInfo) {
		System.out.println("Handling now: " + opcode + " " + instIdx + " " + addInfo);
		OpcodeObj oo = BytecodeCategory.getOpcodeObj(opcode);
		int opcat = oo.getCatId();
		
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, addInfo);
		this.updateTime(fullInst);
		this.updateControlRelation(fullInst);
		this.updatePath(fullInst);
		
		if (BytecodeCategory.controlCategory().contains(opcat) 
				|| opcode == Opcodes.TABLESWITCH 
				|| opcode == Opcodes.LOOKUPSWITCH) {
			this.updateControlInst(fullInst);
		}
		
		int inputSize = oo.getInList().size();
		if (inputSize > 0) {
			for (int i = 0; i < inputSize; i++) {
				//Should not return null here
				InstNode tmpInst = this.safePop();
				this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			}
		}
		this.updateStackSimulator(fullInst, 0);
		this.showStackSimulator();
	}
	
	/**
	 * localVarIdx is not necessarily a local var
	 * @param opcode
	 * @param localVarIdx
	 */
	public void handleOpcode(int opcode, int instIdx, int localVarIdx) {
		System.out.println("Handling now: " + opcode + " " + localVarIdx);
		
		InstNode fullInst = null;
		if (localVarIdx >= 0) {
			fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, String.valueOf(localVarIdx));
		} else {
			fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, "");
		}
		this.updateTime(fullInst);
		
		int opcat = fullInst.getOp().getCatId();
		
		InstNode lastInst = null;
		if (!stackSimulator.isEmpty()) {
			lastInst = stackSimulator.peek();
		}
		
		if (!BytecodeCategory.dupCategory().contains(opcat)) {
			//Dup inst will be replaced later. No need to add any dep
			this.updateControlRelation(fullInst);
		}
		this.updatePath(fullInst);
		
		System.out.println("Check lastInst: " + lastInst);
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
			if (BytecodeCategory.controlCategory().contains(opcat)) {
				this.updateControlInst(fullInst);
			}
			
			int inputSize = fullInst.getOp().getInList().size();
			InstNode lastTmp = null;
			if (inputSize > 0) {
				for (int i = 0; i < inputSize; i++) {
					//Should not return null here
					InstNode tmpInst = this.safePop();
					if (!tmpInst.equals(lastTmp))
						this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
					
					lastTmp = tmpInst;
				}
			}
		}
		
		if (!hasUpdate) 
			this.updateStackSimulator(fullInst, 0);
		this.showStackSimulator();
	}
	
	public void handleMultiNewArray(String desc, int dim, int instIdx) {
		System.out.println("Handling now: " + desc + " " + dim + " " + instIdx);
		String addInfo = desc + " " + dim;
		InstNode fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, Opcodes.MULTIANEWARRAY, addInfo);
		this.updateTime(fullInst);
		
		this.updateControlRelation(fullInst);
		this.updatePath(fullInst);
		
		for (int i = 0; i < dim; i++) {
			InstNode tmpInst = this.safePop();
			this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
		}
		this.updateStackSimulator(fullInst, 0);
		this.showStackSimulator();
	}
	
	private void handleUninstrumentedMethod(int opcode, int instIdx, int linenum, String owner, String name, String desc, InstNode fullInst) {
		System.out.println("Handling uninstrumented method: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		this.updateTime(fullInst);
		
		this.updateControlRelation(fullInst);
		
		Type methodType = Type.getMethodType(desc);
		//+1 for object reference, if instance method
		Type[] args = methodType.getArgumentTypes();
		int argSize = 0;
		for (int i = 0; i < args.length; i++) {
			Type t = args[i];
			if (t.getDescriptor().equals("D") || t.getDescriptor().equals("J")) {
				argSize += 2;
			} else {
				argSize += 1;
			}
		}
		
		if (!BytecodeCategory.staticMethod().contains(opcode)) {
			argSize++;
		}
		System.out.println("Arg size: " + argSize);
		String returnType = methodType.getReturnType().getDescriptor();
		for (int i = 0; i < argSize; i++) {
			InstNode tmpInst = this.safePop();
			this.updateCachedMap(tmpInst, fullInst, MIBConfiguration.INST_DATA_DEP);
			//this.updateInvokeMethod(methodKey, tmpInst);
		}
		
		if (!returnType.equals("V")) {
			if (returnType.equals("D") || returnType.equals("J")) {
				this.updateStackSimulator(2, fullInst);
			} else {
				this.updateStackSimulator(1, fullInst);
			}
		}
		this.showStackSimulator();
	}
	
	public void handleMethod(int opcode, int instIdx, int linenum, String owner, String name, String desc) {
		//String addInfo = owner + "." + name + "." + desc;
		//String addInfo = StringUtil.genKey(owner, name, desc);
		//InstNode fullInst = this.pool.searchAndGet(this.methodKey, instIdx, opcode, addInfo);
		
		System.out.println("Handling now: " + opcode + " " + instIdx + " " + owner + " " + name + " " + desc);
		try {
			Type methodType = Type.getMethodType(desc);
			Type[] args = methodType.getArgumentTypes();
			int argSize = 0;
			for (int i = 0; i < args.length; i++) {
				if (args[i].getSort() == Type.DOUBLE || args[i].getSort() == Type.LONG) {
					argSize += 2;
				} else {
					argSize++;
				}
			}
			System.out.println("Arg size: " + argSize);
			
			//Load the correct graph
			Class<?> correctClass = null;
			int methodId = 0;
			if (BytecodeCategory.staticMethod().contains(opcode)) {
				correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, false);
				methodId = ObjectIdAllocater.getClassMethodIndex(owner, name, desc);
			} else {
				InstNode relatedInst = this.stackSimulator.get(stackSimulator.size() - argSize - 1);
				System.out.println("Related inst: " + relatedInst);
				Object objOnStack = relatedInst.getRelatedObj();
				//System.out.println("Check objOnStack: " + objOnStack);
				methodId = ObjectIdAllocater.parseObjId(objOnStack);
				
				if (opcode == Opcodes.INVOKESPECIAL) {
					correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(owner, name, desc, true);
				} else {
					correctClass = ClassInfoCollector.retrieveCorrectClassByMethod(objOnStack.getClass().getName(), name, desc, false);
				}
			}
			
			System.out.println("Method owner: " + correctClass.getName());
			String methodKey = StringUtil.genKey(correctClass.getName(), name, desc);
			String searchKey = "";
			if (BytecodeCategory.staticMethod().contains(opcode)) {
				searchKey = StringUtil.genKeyWithMethodId(methodKey, 0);
			} else {
				searchKey = StringUtil.genKeyWithMethodId(methodKey, methodId);
			}
			System.out.println("Search key: " + searchKey);
			InstNode fullInst = this.pool.searchAndGet(this.methodKey, this.id, instIdx, opcode, searchKey);
			
			//Don't update, because we will remove inst before leaving the method
			//this.updateControlRelation(fullInst);
			this.updatePath(fullInst);
			
			String filePath = "";
			if (MIBConfiguration.getInstance().isTemplateMode()) {
				filePath = MIBConfiguration.getInstance().getTemplateDir() + "/" + searchKey + ".json";
			} else {
				filePath = MIBConfiguration.getInstance().getTestDir() + "/" + searchKey + ".json";
			}
			GraphTemplate childGraph = TemplateLoader.loadTemplateFile(filePath, graphToken);
			
			if (childGraph != null && BytecodeCategory.staticMethod().contains(opcode)) {
				System.out.println("Reset class method inst id: " + methodId);
				GraphUtil.setStaticMethodIdx(childGraph, methodId);
			}
			
			//This means that the callee method is from jvm, keep the method inst in graph
			if (childGraph == null) {
				System.out.println("Null graph: " + searchKey);
				this.handleUninstrumentedMethod(opcode, instIdx, linenum, owner, name, desc, fullInst);
				return ;
			}
			
			System.out.println("Child graph: " + childGraph.getMethodKey() + " " + childGraph.getInstPool().size());
						
			InstPool childPool = childGraph.getInstPool();
			GraphUtil.removeReturnInst(childPool);
			
			//Reindex child
			int baseTime = this.getCurTime();
			int reBase = GraphUtil.reindexInstPool(baseTime, childPool);
			this.curTime.set(reBase);
			
			//Search correct inst, update local data dep dependency
			HashMap<Integer, InstNode> parentFromCaller = new HashMap<Integer, InstNode>();
			if (args.length > 0) {
				int startIdx = 0;
				if (!BytecodeCategory.staticMethod().contains(opcode)) {
					startIdx = 1;
				}
				int endIdx = startIdx + args.length - 1;
				
				for (int i = args.length - 1; i >= 0 ;i--) {
					Type t = args[i];
					InstNode targetNode = null;
					if (t.getDescriptor().equals("D") || t.getDescriptor().equals("J")) {
						this.safePop();
						targetNode = this.safePop();
					} else {
						targetNode = this.safePop();
					}
					parentFromCaller.put(endIdx--, targetNode);
				}
			}
			
			if (!BytecodeCategory.staticMethod().contains(opcode)) {
				//loadNode can be anyload that load an object
				InstNode loadNode = this.safePop();
				parentFromCaller.put(0, loadNode);
			}
			
			GraphUtil.dataDepFromParentToChild(parentFromCaller, this.pool, childGraph);
			
			//Update control dep
			if (this.curControlInst != null) {
				GraphUtil.controlDepFromParentToChild(this.curControlInst, childPool);
			}
			
			//Update field data dep
			if (this.fieldRecorder.size() > 0) {
				GraphUtil.fieldDataDepFromParentToChild(this.fieldRecorder, childGraph);
				this.firstReadFields.addAll(childGraph.getFirstReadFields());
			}
			
			if (childGraph.getWriteFields().size() > 0) {
				this.fieldRecorder.putAll(childGraph.getWriteFields());
			}
			
			String returnType = methodType.getReturnType().getDescriptor();
			if (!returnType.equals("V")) {
				InstNode lastSecond = GraphUtil.lastSecondInst(childGraph.getInstPool());
				if (returnType.equals("D") || returnType.equals("J")) {
					if (lastSecond != null)
						this.updateStackSimulator(2, lastSecond);
				} else {
					this.updateStackSimulator(1, lastSecond);
				}
			}
			this.showStackSimulator();
			this.pool.remove(fullInst);
			GraphUtil.unionInstPools(this.pool, childPool);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	public void handleDup(int opcode) {
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
	
	private void updateStackSimulator(InstNode fullInst, int addOutput) {
		int outputSize = fullInst.getOp().getOutList().size() + addOutput;
		this.updateStackSimulator(outputSize, fullInst);
	}
	
	private void updateStackSimulator(int times, InstNode fullInst) {
		System.out.println("Stack push: " + fullInst + " " + times);
		for (int i = 0; i < times; i++) {
			this.stackSimulator.push(fullInst);
		}
	}
	
	private void showStackSimulator() {
		System.out.println(this.stackSimulator);
	}
	
	private void updateControlRelation(InstNode fullInst) {		
		if (this.curControlInst != null) {
			int cCatId = curControlInst.getOp().getCatId();
			
			//Get the last second, because the current node is in the pool
			InstNode lastNode = null;
			if (this.path.size() > 0)
				lastNode = this.path.get(this.path.size() - 1);
			
			if (BytecodeCategory.controlCategory().contains(cCatId)) {
				if (lastNode != null && lastNode.equals(this.curControlInst)) {
					if (!this.curControlInst.getAddInfo().equals(this.curLabel)) {
						this.curControlInst = null;
						return ;
					}
				}
			} else {
				//TableSwitch and LookupSwitch
				String[] allLabels = curControlInst.getAddInfo().split(",");
				boolean found = false;
				for (String l: allLabels) {
					if (l.equals(this.curLabel)) {
						found = true;
						break;
					}
				}
				
				if (!found) {
					this.curControlInst = null;
					return ;
				}
			}
			
			if (!BytecodeCategory.dupCategory().contains(fullInst.getOp().getCatId()))
				this.updateCachedMap(this.curControlInst, fullInst, MIBConfiguration.CONTR_DEP);
		}
	}
	
	public void dumpGraph() {		
		//For serilization
		GraphTemplate gt = new GraphTemplate();
		
		gt.setMethodKey(this.methodKey);
		/*if (this.staticMethod) {
			gt.setMethodId(ObjectIdAllocater.getClassMethodIndex(this.className, this.methodName, this.methodDesc));
		} else {
			gt.setMethodId(this.id);
		}*/
		gt.setMethodId(this.id);
		gt.setMethodArgSize(this.methodArgSize);
		gt.setMethodReturnSize(this.methodReturnSize);
		gt.setStaticMethod(this.staticMethod);
		gt.setMaxTime(this.maxTime);
		gt.setFirstReadLocalVars(this.firstReadLocalVars);
		gt.setFirstReadFields(this.firstReadFields);
		gt.setWriteFields(this.fieldRecorder);
		gt.setPath(this.path);
		
		GraphUtil.transplantFirstSurrogate(this.pool);
		
		System.out.println("Instruction dependency:");
		int depCount = 0;
		Iterator<InstNode> instIterator = this.pool.iterator();
		while (instIterator.hasNext()) {
			InstNode curInst = instIterator.next();
			TreeMap<String, Double> children = curInst.getChildFreqMap();
			depCount += children.size();
		}
		System.out.println("Total dependency count: " + depCount);
		
		gt.setInstPool(this.pool);
		
		TypeToken<GraphTemplate> typeToken = new TypeToken<GraphTemplate>(){};
		
		String dumpKey = StringUtil.genKeyWithMethodId(this.methodKey, this.id);
		if (MIBConfiguration.getInstance().isTemplateMode()) {
			GsonManager.writeJsonGeneric(gt, dumpKey, typeToken, 0);
		} else {
			GsonManager.writeJsonGeneric(gt, dumpKey, typeToken, 1);
		}
		GsonManager.writePath(dumpKey, this.path);
	}

}
