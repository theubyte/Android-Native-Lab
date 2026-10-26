#include "slicer.hpp"
#include <algorithm>
#include <cmath>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace layerforge {
namespace {
constexpr std::size_t maxSourceBytes=60ULL*1024*1024;
void bound(Mesh& m) {
    if(m.triangles.empty())throw std::runtime_error("OBJ contains no faces");
    m.min={1e100,1e100,1e100};m.max={-1e100,-1e100,-1e100};
    for(const auto& t:m.triangles)for(const auto& v:{t.a,t.b,t.c}){
        if(!std::isfinite(v.x)||!std::isfinite(v.y)||!std::isfinite(v.z))
            throw std::runtime_error("OBJ contains nonfinite vertices");
        m.min.x=std::min(m.min.x,v.x);m.min.y=std::min(m.min.y,v.y);m.min.z=std::min(m.min.z,v.z);
        m.max.x=std::max(m.max.x,v.x);m.max.y=std::max(m.max.y,v.y);m.max.z=std::max(m.max.z,v.z);
    }
}
std::size_t vertexIndex(const std::string& item,std::size_t count) {
    auto separator=item.find('/');
    auto field=item.substr(0,separator);
    if(field.empty())throw std::runtime_error("OBJ face has empty vertex reference");
    std::size_t used=0;
    long long index=std::stoll(field,&used);
    if(used!=field.size()||index==0)throw std::runtime_error("Invalid OBJ face index");
    long long resolved=index>0?index-1:static_cast<long long>(count)+index;
    if(resolved<0||static_cast<unsigned long long>(resolved)>=count)
        throw std::runtime_error("OBJ face references unavailable vertex");
    return static_cast<std::size_t>(resolved);
}
}
Mesh parseMesh(const std::uint8_t* data,std::size_t size) {
    if(!data||size<15||size>maxSourceBytes)throw std::runtime_error("Invalid or oversized model");
    // OBJ is textual; binary STLs are forwarded to the existing STL parser.
    if(std::find(data,data+size,static_cast<std::uint8_t>(0))!=data+size)
        return parseStl(data,size);
    std::string source(reinterpret_cast<const char*>(data),size);
    std::istringstream sniff(source);std::string line;
    bool obj=false;
    for(int i=0;i<200 && std::getline(sniff,line);++i){
        if(line.rfind("v ",0)==0||line.rfind("f ",0)==0){obj=true;break;}
    }
    if(!obj)return parseStl(data,size);
    std::istringstream lines(source);
    std::vector<Vec3> vertices;
    Mesh mesh;
    while(std::getline(lines,line)){
        std::istringstream row(line);
        std::string kind;row>>kind;
        if(kind=="v"){
            Vec3 p;
            if(!(row>>p.x>>p.y>>p.z))throw std::runtime_error("Malformed OBJ vertex");
            if(!std::isfinite(p.x)||!std::isfinite(p.y)||!std::isfinite(p.z))
                throw std::runtime_error("Nonfinite OBJ vertex");
            if(vertices.size()>=800000)throw std::runtime_error("Too many OBJ vertices");
            vertices.push_back(p);
        }else if(kind=="f"){
            std::vector<std::size_t> ids;
            std::string ref;
            while(row>>ref){
                if(!ref.empty()&&ref[0]=='#')break;
                ids.push_back(vertexIndex(ref,vertices.size()));
                if(ids.size()>10000)throw std::runtime_error("OBJ polygon too large");
            }
            if(ids.size()<3)throw std::runtime_error("OBJ face needs at least 3 vertices");
            for(std::size_t i=1;i+1<ids.size();++i){
                mesh.triangles.push_back({vertices[ids[0]],vertices[ids[i]],vertices[ids[i+1]]});
                if(mesh.triangles.size()>800000)throw std::runtime_error("OBJ triangle limit exceeded");
            }
        }
    }
    bound(mesh);
    return mesh;
}
}
