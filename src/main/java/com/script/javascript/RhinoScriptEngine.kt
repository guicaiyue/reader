package com.script.javascript

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings
import javax.script.Bindings

class RhinoScriptEngine {
    private val engine: ScriptEngine = ScriptEngineManager().getEngineByName("rhino")
        ?: ScriptEngineManager().getEngineByName("javascript")
        ?: throw RuntimeException("No JavaScript engine found")
    
    fun eval(script: String): Any? {
        return engine.eval(script)
    }
    
    fun eval(script: String, bindings: Bindings): Any? {
        return engine.eval(script, bindings)
    }
    
    fun createBindings(): Bindings {
        return SimpleBindings()
    }
    
    fun put(key: String, value: Any?) {
        engine.put(key, value)
    }
    
    fun get(key: String): Any? {
        return engine.get(key)
    }
}