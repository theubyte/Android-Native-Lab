#include <jni.h>
#include "slicer.hpp"
#include <fstream>
#include <stdexcept>
#include <vector>
using namespace layerforge;
namespace {
class UtfString {
public:
 UtfString(JNIEnv* e,jstring s):env(e),ref(s),ptr(s?e->GetStringUTFChars(s,nullptr):nullptr){}
 ~UtfString(){if(ptr)env->ReleaseStringUTFChars(ref,ptr);}
 const char* get() const{return ptr;}
private:JNIEnv* env;jstring ref;const char* ptr;
};
Mesh load(JNIEnv* env,jbyteArray bytes){
 if(!bytes)throw std::runtime_error("No STL loaded");
 jsize size=env->GetArrayLength(bytes);
 if(size<=0||size>60*1024*1024)throw std::runtime_error("STL size out of bounds");
 std::vector<std::uint8_t> buffer(static_cast<std::size_t>(size));
 env->GetByteArrayRegion(bytes,0,size,reinterpret_cast<jbyte*>(buffer.data()));
 if(env->ExceptionCheck())throw std::runtime_error("Could not read STL bytes");
 return parseStl(buffer.data(),buffer.size());
}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_de_theubyte_layerforge_NativeSlicer_inspect(JNIEnv* env,jobject,jbyteArray bytes){
 try{
   auto p=preview(load(env,bytes));
   jfloatArray array=env->NewFloatArray(static_cast<jsize>(p.size()));
   if(array)env->SetFloatArrayRegion(array,0,static_cast<jsize>(p.size()),p.data());
   return array;
 }catch(const std::exception&){return env->NewFloatArray(0);}
}
extern "C" JNIEXPORT jstring JNICALL
Java_de_theubyte_layerforge_NativeSlicer_slice(JNIEnv* env,jobject,jbyteArray bytes,jstring target,
 jdouble layerHeight,jdouble lineWidth,jint walls,jint infill,jdouble speed,jdouble travel,
 jint nozzleC,jint bedC,jdouble filament,jint topBottom,jdouble bedX,jdouble bedY,jdouble bedZ){
 try{
   UtfString path(env,target);
   if(!path.get())throw std::runtime_error("Missing output path");
   Settings cfg;cfg.layerHeight=layerHeight;cfg.lineWidth=lineWidth;
   cfg.walls=walls;cfg.infillPercent=infill;cfg.printSpeed=speed;cfg.travelSpeed=travel;
   cfg.nozzleC=nozzleC;cfg.bedC=bedC;cfg.filamentDiameter=filament;
   cfg.topBottomLayers=topBottom;cfg.bedWidth=bedX;cfg.bedDepth=bedY;cfg.bedHeight=bedZ;
   auto result=layerforge::slice(load(env,bytes),cfg);
   if(!result.ok) return env->NewStringUTF(("ERROR: "+result.message).c_str());
   std::ofstream f(path.get(),std::ios::binary|std::ios::trunc);
   if(!f)throw std::runtime_error("Unable to write G-code");
   f<<result.gcode;f.flush();
   if(!f)throw std::runtime_error("Unable to finish G-code export");
   return env->NewStringUTF(("OK: "+result.message).c_str());
 }catch(const std::exception& ex){return env->NewStringUTF(("ERROR: "+std::string(ex.what())).c_str());}
}
