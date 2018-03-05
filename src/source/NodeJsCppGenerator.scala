package djinni

import java.util.ListResourceBundle


import djinni.ast._
import djinni.generatorTools._
import djinni.meta._

import scala.collection.mutable.ListBuffer

class NodeJsCppGenerator(spec: Spec) extends NodeJsGenerator(spec) {

  override def generate(idl: Seq[TypeDecl]): Unit = {

    super.generate(idl)

    //Create file with all interfaces
    val fileName = spec.nodePackage + ".cpp"
    createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>

      w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
      w.wl("// This file generated by Djinni")
      w.wl
      w.wl("#include <nan.h>")
      w.wl("#include <node.h>")
      w.wl
      var interfacesToInit = new ListBuffer[TypeDecl]()
      for (td <- idl.collect { case itd: InternTypeDecl => itd }) td.body match {
        case e: Enum =>
        case r: Record =>
        case i: Interface =>
          //First include headers
          var headerName = if (i.ext.nodeJS) idNode.ty(td.ident.name) else idNode.ty(td.ident.name) + "Cpp"
          w.wl("#include \"" + headerName + "." + spec.cppHeaderExt + "\"")
          interfacesToInit += td
      }
      w.wl
      w.wl("using namespace v8;")
      w.wl("using namespace node;")
      w.wl
      w.wl("static void initAll(Local<Object> target)").braced {
        w.wl("Nan::HandleScope scope;")
        for (td <- interfacesToInit) {
          val baseClassName = marshal.typename(td.ident, td.body)
          w.wl(s"$baseClassName::Initialize(target);")
        }

      }
      w.wl(s"NODE_MODULE(${spec.nodePackage},initAll);")
    })
  }

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface): Unit = {

    val isNodeMode = false

    //Generate header file
    generateInterface(origin, ident, doc, typeParams, i, isNodeMode)

    //Generate implementation file
    val baseClassName = marshal.typename(ident, i)
    val cppClassName = cppMarshal.typename(ident, i)

    if (i.ext.cpp) {

      val fileName = idNode.ty(ident.name) + "Cpp.cpp"
      createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>
        w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
        w.wl("// This file generated by Djinni from " + origin)
        w.wl
        val hppFileName = "#include \"" + idNode.ty(ident.name) + "Cpp." + spec.cppHeaderExt + "\""
        w.wl(hppFileName)
        w.wl
        w.wl("using namespace v8;")
        w.wl("using namespace node;")
        w.wl("using namespace std;")
        w.wl
        var factory: Option[Interface.Method] = None
        var factoryFound = false
        for (m <- i.methods) {
          val methodName = m.ident.name
          w.w(s"NAN_METHOD($baseClassName::$methodName)").braced {
            val argsLength = m.params.length
            w.wl
            w.wl("//Check if method called with right number of arguments")
            w.wl(s"if(info.Length() != $argsLength)").braced {
              val error = s""""$baseClassName::$methodName needs $argsLength arguments""""
              w.wl(s"return Nan::ThrowError($error);")
            }

            w.wl
            w.wl("//Check if parameters have correct types")
            //Retrieve all method’s parameter and test their types
            val countArgs = checkAndCastTypes(ident, i, m, w)
            var args: String = ""
            for (i <- 0 to countArgs - 1) {
              args = s"${args}arg_$i"
              if (i < m.params.length - 1) {
                args = s"${args},"
              }
            }

            w.wl
            w.wl("//Unwrap current object and retrieve its Cpp Implementation")
            w.wl(s"$baseClassName* obj = Nan::ObjectWrap::Unwrap<$baseClassName>(info.This());")
            w.wl(s"auto cpp_impl = obj->getCppImpl();")

            //Test if implementation is null
            w.wl(s"if(!cpp_impl)").braced {
              val error = s""""$baseClassName::$methodName : implementation of $cppClassName is not valid""""
              w.wl(s"return Nan::ThrowError($error);")
            }

            val cppRet = cppMarshal.returnType(m.ret)
            if (m.ret.isDefined && cppRet != "void") {
              w.wl
              w.wl(s"auto result = cpp_impl->$methodName($args);")
              w.wl
              w.wl("//Wrap result in node object")
              marshal.fromCppArgument(m.ret.get.resolved, s"arg_$countArgs", "result", w)
              w.wl
              w.wl("//Return result")
              w.wl(s"info.GetReturnValue().Set(arg_$countArgs);")
            } else {
              w.wl(s"cpp_impl->$methodName($args);")
            }
          }
          //Get factory method if it exists (will be used for Nan::New method)
          if (!factoryFound && m.static) {
            factoryFound = m.ret.exists { x =>
              val returnTypeName = cppMarshal.paramType(x.resolved, true)
              returnTypeName.equals(s"std::shared_ptr<$cppClassName>")
            }
            if (factoryFound) {
              factory = Some(m)
            }
          }
        }
        w.wl
        createNanNewMethod(ident, i, factory, w)
        w.wl
        createWrapMethod(ident, i, w)
        w.wl
        createInitializeMethod(ident, i, w)
      })
    }
  }

  def createWrapMethod(ident: Ident, i: Interface, wr: writer.IndentWriter): Unit = {
    val baseClassName = marshal.typename(ident, i)
    val cppClassName = cppMarshal.typename(ident, i)
    val cpp_shared_ptr = "std::shared_ptr<" + spec.cppNamespace + "::" + cppClassName + ">"
    wr.wl
    wr.wl(s"Nan::Persistent<ObjectTemplate> $baseClassName::${cppClassName}_prototype;")
    wr.wl
    wr.w(s"Handle<Object> $baseClassName::wrap(const $cpp_shared_ptr &object)").braced {
      wr.wl(s"Local<ObjectTemplate> local_prototype = Nan::New(${cppClassName}_prototype);")
      wr.wl
      wr.wl("Handle<Object> obj;")
      wr.wl("if(!local_prototype.IsEmpty())").braced {
        wr.wl("obj = local_prototype->NewInstance();")
        wr.wl(s"""$baseClassName *new_obj = new $baseClassName(object);""")
        wr.wl("if(new_obj)").braced {
          wr.wl("new_obj->Wrap(obj);")
          wr.wl("new_obj->Ref();")
        }
      }
      wr.wl("else").braced {
        val error = s""""$baseClassName::wrap: object template not valid""""
        wr.wl(s"Nan::ThrowError($error);")
      }
      wr.wl("return obj;")
    }
  }
}


