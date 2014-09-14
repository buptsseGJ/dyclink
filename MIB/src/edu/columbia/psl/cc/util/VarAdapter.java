package edu.columbia.psl.cc.util;

import java.lang.reflect.Type;
import java.util.HashSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import edu.columbia.psl.cc.pojo.LocalVar;
import edu.columbia.psl.cc.pojo.ObjVar;
import edu.columbia.psl.cc.pojo.Var;

public class VarAdapter implements JsonSerializer<Var>, JsonDeserializer<Var>{

	@Override
	public Var deserialize(JsonElement json, Type typeOfT,
			JsonDeserializationContext context) throws JsonParseException {
		JsonObject object = json.getAsJsonObject();
		Var var = null;
		
		JsonElement ncClassElement = object.get("nativeClassElement");
		JsonElement varNameElement = object.get("varName");
		JsonElement varIdElement = object.get("localVarId");
		if (ncClassElement != null) {
			var = new ObjVar();
			String ncClassName = ncClassElement.getAsString();
			String varName = varNameElement.getAsString();
			((ObjVar)var).setNativeClassName(ncClassName);
			((ObjVar)var).setVarName(varName);
		} else if (varIdElement != null) {
			var = new LocalVar();
			int localVarId = varIdElement.getAsInt();
			((LocalVar)var).setLocalVarId(localVarId);
		}
		
		JsonElement classElement = object.get("className");
		JsonElement methodElement = object.get("methodName");
		JsonElement silIdElement = object.get("silId");
		JsonElement opcodeElement = object.get("opcode");
		JsonArray childrenElement = object.get("children").getAsJsonArray();
		HashSet<Var> children = new HashSet<Var>();
		for (int i = 0; i < childrenElement.size(); i++) {
			JsonElement element = childrenElement.get(i);
			Var tmpVar = context.<Var>deserialize(element, Var.class);
			children.add(tmpVar);
		}
		
		var.setClassName(classElement.getAsString());
		var.setMethodName(methodElement.getAsString());
		var.setSilId(silIdElement.getAsInt());
		var.setOpcode(opcodeElement.getAsInt());
		var.setChildren(children);
		return var;
	}

	@Override
	public JsonElement serialize(Var var, Type typeOfSrc,
			JsonSerializationContext context) {
		JsonObject result = new JsonObject();
		result.addProperty("className", var.getClassName());
		result.addProperty("methodName", var.getMethodName());
		result.addProperty("silId", var.getSilId());
		result.addProperty("opcode", var.getOpcode());
		JsonElement varChildren = context.serialize(var.getChildren());
		result.add("children", varChildren);
		if (var.getSilId() < 2) {
			ObjVar ov = (ObjVar)var;
			result.addProperty("nativeClassName", ov.getNativeClassName());
			result.addProperty("varName", ov.getVarName());
		} else {
			LocalVar lv = (LocalVar)var;
			result.addProperty("localVarId", lv.getLocalVarId());
		}
		return result;
	}

}
