#include "slicer.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace layerforge {
namespace {
constexpr double EPS=1e-7;
constexpr double PI=3.14159265358979323846;
double cross(Vec2 a,Vec2 b) { return a.x*b.y-a.y*b.x; }
Vec2 operator+(Vec2 a,Vec2 b){return {a.x+b.x,a.y+b.y};}
Vec2 operator-(Vec2 a,Vec2 b){return {a.x-b.x,a.y-b.y};}
Vec2 operator*(Vec2 a,double t){return {a.x*t,a.y*t};}
double norm(Vec2 v){return std::hypot(v.x,v.y);}
void ensureFinite(Vec3 p){
    if(!std::isfinite(p.x)||!std::isfinite(p.y)||!std::isfinite(p.z))
       throw std::runtime_error("STL contains non-finite vertices");
}
float readFloat(const std::uint8_t* p) {
    std::uint32_t u=static_cast<std::uint32_t>(p[0]) | (static_cast<std::uint32_t>(p[1])<<8)
                 | (static_cast<std::uint32_t>(p[2])<<16) | (static_cast<std::uint32_t>(p[3])<<24);
    float f; std::memcpy(&f,&u,sizeof(f)); return f;
}
std::uint32_t read32(const std::uint8_t* p){
    return static_cast<std::uint32_t>(p[0]) | (static_cast<std::uint32_t>(p[1])<<8)
       | (static_cast<std::uint32_t>(p[2])<<16) | (static_cast<std::uint32_t>(p[3])<<24);
}
void bounds(Mesh& m) {
    if(m.triangles.empty()) throw std::runtime_error("STL contains no triangles");
    m.min={1e100,1e100,1e100};m.max={-1e100,-1e100,-1e100};
    for(auto& t:m.triangles) for(auto& v:{t.a,t.b,t.c}) {
        ensureFinite(v);
        m.min.x=std::min(m.min.x,v.x);m.min.y=std::min(m.min.y,v.y);m.min.z=std::min(m.min.z,v.z);
        m.max.x=std::max(m.max.x,v.x);m.max.y=std::max(m.max.y,v.y);m.max.z=std::max(m.max.z,v.z);
    }
}
using Path=std::vector<Vec2>;
using Paths=std::vector<Path>;
// A non-coplanar triangle usually intersects a slicing plane in exactly two points.
bool crossSection(const Triangle& t, double z, Vec2& a,Vec2& b) {
    std::array<Vec3,3> p={t.a,t.b,t.c};
    std::vector<Vec2> hits;hits.reserve(3);
    for(int j=0;j<3;++j){
        const Vec3& u=p[j];const Vec3& v=p[(j+1)%3];
        // Half-open rule avoids counting shared plane vertices twice.
        if((u.z<=z && v.z>z)||(v.z<=z && u.z>z)) {
            double t2=(z-u.z)/(v.z-u.z);
            Vec2 hit{u.x+t2*(v.x-u.x),u.y+t2*(v.y-u.y)};
            if(std::all_of(hits.begin(),hits.end(),[&](Vec2 q){return norm(q-hit)>EPS;})) hits.push_back(hit);
        }
    }
    if(hits.size()!=2||norm(hits[1]-hits[0])<EPS)return false;
    a=hits[0];b=hits[1];return true;
}
using Key=std::pair<std::int64_t,std::int64_t>;
Key pointKey(Vec2 p) {return {std::llround(p.x*10000),std::llround(p.y*10000)};}
struct Edge {Vec2 a,b;Key ka,kb;bool used=false;};
Paths sectionLoops(const Mesh& mesh,double z) {
    std::vector<Edge> edges;edges.reserve(mesh.triangles.size()/2);
    std::map<Key,std::vector<std::size_t>> adjacency;
    std::set<std::pair<Key,Key>> unique;
    for(const auto& tri:mesh.triangles){
        Vec2 a,b;if(!crossSection(tri,z,a,b))continue;
        Key ka=pointKey(a),kb=pointKey(b);
        if(ka==kb)continue;
        auto pair=std::minmax(ka,kb);
        if(!unique.emplace(pair.first,pair.second).second) continue;
        auto id=edges.size();edges.push_back({a,b,ka,kb,false});
        adjacency[ka].push_back(id);adjacency[kb].push_back(id);
    }
    Paths loops;
    for(std::size_t start=0;start<edges.size();++start){
        if(edges[start].used)continue;
        Path loop;auto cur=start;
        Key first=edges[start].ka,point=first;
        bool closed=false;
        std::size_t safeguard=0;
        while(safeguard++<=edges.size()){
            Edge& e=edges[cur];
            if(e.used)break;
            e.used=true;
            bool forwards=(e.ka==point);
            if(!forwards && e.kb!=point)break;
            loop.push_back(forwards?e.a:e.b);
            point=forwards?e.kb:e.ka;
            if(point==first){closed=true;break;}
            auto it=adjacency.find(point);if(it==adjacency.end())break;
            bool found=false;
            for(auto next:it->second)if(!edges[next].used){cur=next;found=true;break;}
            if(!found)break;
        }
        if(!closed) throw std::runtime_error("Open mesh section detected: repair the STL before slicing");
        if(loop.size()>=3) loops.push_back(std::move(loop));
    }
    return loops;
}
double signedArea(const Path& p) {
    double s=0;for(std::size_t i=0;i<p.size();++i)s+=cross(p[i],p[(i+1)%p.size()]);return 0.5*s;
}
bool contains(const Path& p,Vec2 q) {
    bool inside=false;
    for(std::size_t i=0,j=p.size()-1;i<p.size();j=i++){
        auto a=p[i],b=p[j];
        if((a.y>q.y)!=(b.y>q.y) && q.x<(b.x-a.x)*(q.y-a.y)/(b.y-a.y)+a.x)inside=!inside;
    }
    return inside;
}
void orientPaths(Paths& paths) {
    for(std::size_t i=0;i<paths.size();++i){
        int depth=0;
        for(std::size_t j=0;j<paths.size();++j)if(i!=j && contains(paths[j],paths[i][0]))++depth;
        bool ccw=(depth%2==0);
        if((signedArea(paths[i])>0)!=ccw)std::reverse(paths[i].begin(),paths[i].end());
    }
}
// Inward parallel-line intersections. Only a basic polygon offset; not a robust polygon clipper.
Path inset(const Path& p,double dist) {
    Path out;out.reserve(p.size());
    for(std::size_t i=0;i<p.size();++i){
        Vec2 prev=p[(i+p.size()-1)%p.size()],v=p[i],next=p[(i+1)%p.size()];
        Vec2 a=v-prev,b=next-v;
        double la=norm(a),lb=norm(b);
        if(la<EPS || lb<EPS){out.push_back(v);continue;}
        a=a*(1/la);b=b*(1/lb);
        Vec2 na{-a.y,a.x},nb{-b.y,b.x};
        double den=cross(a,b);
        Vec2 shifted=v+na*dist;
        Vec2 candidate=std::abs(den)<1e-8 ? v+(na+nb)*(dist*0.5)
             : shifted+a*(cross((v+nb*dist)-shifted,b)/den);
        // Avoid pathological 1000x miters on reflex corners.
        if(norm(candidate-v)>std::max(4.0*dist,0.01))candidate=v+(na+nb)*(dist*0.5);
        out.push_back(candidate);
    }
    return out;
}
Paths insetAll(const Paths& p,double dist){
    Paths r; r.reserve(p.size());
    for(auto& path:p)if(path.size()>=3){auto o=inset(path,dist);if(std::abs(signedArea(o))>0.01)r.push_back(std::move(o));}
    return r;
}
class Writer {
public:
    explicit Writer(Settings s):cfg(s){stream<<std::fixed<<std::setprecision(4);}
    void header(){
        stream<<"; LayerForge experimental native slicing engine\n";
        stream<<"; Review G-code and printer start/end macros before printing\n";
        stream<<"G21\nG90\nM83\n";
        stream<<"M140 S"<<cfg.bedC<<"\nM104 S"<<cfg.nozzleC<<"\n";
        stream<<"M190 S"<<cfg.bedC<<"\nM109 S"<<cfg.nozzleC<<"\nG28\nG92 E0\n";
    }
    void setLayer(int n,double z){
        if(n==1)stream<<"M106 S"<<(cfg.fanPercent*255/100)<<"\n";
        stream<<";LAYER:"<<n<<"\nG0 Z"<<z<<" F"<<cfg.travelSpeed*60<<"\n";
    }
    void path(const Path& p,bool closed){
        if(p.size()<2)return;
        travel(p[0]);
        auto last=p[0];
        for(std::size_t i=1;i<p.size()+(closed?1:0);++i){
            Vec2 now=p[i%p.size()];double distance=norm(now-last);
            if(distance>EPS){
                stream<<"G1 X"<<now.x<<" Y"<<now.y<<" E"<<extrusion(distance)
                      <<" F"<<cfg.printSpeed*60<<"\n";++moves;
                position=now; extruded=true;
            }
            last=now;
        }
    }
    void footer(){stream<<"M104 S0\nM140 S0\nM107\nM84\n";}
    std::string str()const{return stream.str();}
    std::size_t moves=0;
private:
    Settings cfg;std::ostringstream stream;
    Vec2 position{};
    bool positioned=false,extruded=false;
    void travel(Vec2 q){
        double length=positioned?norm(q-position):0;
        bool retract=extruded && cfg.retractionMm>0 && length>=cfg.retractMinTravelMm;
        if(retract) stream<<"G1 E-"<<cfg.retractionMm<<" F1500\n";
        if(!positioned || length>EPS)
            stream<<"G0 X"<<q.x<<" Y"<<q.y<<" F"<<cfg.travelSpeed*60<<"\n";
        if(retract) stream<<"G1 E"<<cfg.retractionMm<<" F1500\n";
        position=q;positioned=true;
    }
    double extrusion(double length)const{
        double filamentArea=PI*std::pow(cfg.filamentDiameter*0.5,2);
        return length*cfg.layerHeight*cfg.lineWidth/filamentArea;
    }
};
void fill(Writer& writer,const Paths& boundaries,double spacing,bool alongX){
    if(boundaries.empty())return;
    double low=1e100,high=-1e100;
    for(auto& p:boundaries)for(auto v:p){double coordinate=alongX?v.y:v.x;
        low=std::min(low,coordinate);high=std::max(high,coordinate);}
    if(high<low || high-low>2000)throw std::runtime_error("Invalid section bounds");
    int idx=0;
    for(double level=low+spacing*0.5;level<high;level+=spacing){
        std::vector<double> intersections;
        for(auto& p:boundaries)for(std::size_t i=0;i<p.size();++i){
            Vec2 a=p[i],b=p[(i+1)%p.size()];
            double va=alongX?a.y:a.x,vb=alongX?b.y:b.x;
            if((va<=level && vb>level)||(vb<=level && va>level)){
                double t=(level-va)/(vb-va);
                intersections.push_back(alongX? a.x+t*(b.x-a.x):a.y+t*(b.y-a.y));
            }
        }
        std::sort(intersections.begin(),intersections.end());
        if(intersections.size()%2)throw std::runtime_error("Odd infill intersections: possibly invalid model");
        for(std::size_t k=0;k+1<intersections.size();k+=2){
            auto lo=intersections[k],hi=intersections[k+1];
            if(hi-lo<0.1)continue;
            Path segment=alongX ? Path{{lo,level},{hi,level}} : Path{{level,lo},{level,hi}};
            if((idx++%2)==1)std::reverse(segment.begin(),segment.end());
            writer.path(segment,false);
        }
    }
}
void checkSettings(const Settings& s){
    if(s.layerHeight<0.05||s.layerHeight>0.6||s.lineWidth<0.2||s.lineWidth>1.2
     ||s.walls<1||s.walls>6||s.infillPercent<0||s.infillPercent>100
     ||s.printSpeed<5||s.printSpeed>300||s.travelSpeed<10||s.travelSpeed>600
     ||s.filamentDiameter<1.0||s.filamentDiameter>3.2||s.bedWidth<20||s.bedDepth<20
     ||s.bedHeight<20||s.topBottomLayers<0||s.topBottomLayers>20
     ||s.nozzleC<0||s.nozzleC>350||s.bedC<0||s.bedC>150
     ||!std::isfinite(s.retractionMm)||s.retractionMm<0||s.retractionMm>5
     ||!std::isfinite(s.retractMinTravelMm)||s.retractMinTravelMm<0.1||s.retractMinTravelMm>50
     ||s.fanPercent<0||s.fanPercent>100)
       throw std::runtime_error("Slicing parameters are out of supported range");
}
}
Mesh parseStl(const std::uint8_t* data,std::size_t size){
    if(!data||size<15||size>60*1024*1024)throw std::runtime_error("Empty or oversized STL (60 MB limit)");
    Mesh mesh;
    bool binary=false;
    if(size>=84){
        auto count=read32(data+80);
        binary=count>0 && count<=800000 && static_cast<std::uint64_t>(84)+static_cast<std::uint64_t>(count)*50==size;
    }
    if(binary){
        auto count=read32(data+80);mesh.triangles.reserve(count);
        for(std::uint32_t i=0;i<count;i++){
            const auto* p=data+84+50ULL*i+12;
            auto vertex=[&](int n)->Vec3{return {readFloat(p+n*12),readFloat(p+n*12+4),readFloat(p+n*12+8)};};
            mesh.triangles.push_back({vertex(0),vertex(1),vertex(2)});
        }
    }else{
        std::string str(reinterpret_cast<const char*>(data),size);
        std::istringstream stream(str);std::string word;
        std::array<Vec3,3> vertices;int n=0;
        while(stream>>word){
            if(word=="vertex"){
                Vec3 v;
                if(!(stream>>v.x>>v.y>>v.z))throw std::runtime_error("Malformed ASCII STL vertex");
                vertices[n++]=v;
                if(n==3){mesh.triangles.push_back({vertices[0],vertices[1],vertices[2]});n=0;}
                if(mesh.triangles.size()>800000)throw std::runtime_error("STL triangle limit exceeded");
            }
        }
        if(n!=0)throw std::runtime_error("Incomplete STL triangle");
    }
    bounds(mesh);return mesh;
}
Result slice(const Mesh& mesh,const Settings& cfg){
    Result result;
    try{
        checkSettings(cfg);
        double sx=mesh.max.x-mesh.min.x,sy=mesh.max.y-mesh.min.y,sz=mesh.max.z-mesh.min.z;
        if(sx<=EPS||sy<=EPS||sz<=EPS)throw std::runtime_error("STL has a zero-size dimension");
        if(sx>cfg.bedWidth-2||sy>cfg.bedDepth-2||sz>cfg.bedHeight)
            throw std::runtime_error("Model exceeds printable bed bounds (1 mm XY margin)");
        // Place the original geometry at the center of the print bed, grounded at Z=0.
        Mesh placed=mesh;
        double dx=(cfg.bedWidth-sx)*0.5-mesh.min.x,dy=(cfg.bedDepth-sy)*0.5-mesh.min.y;
        for(auto& t:placed.triangles)for(Vec3* v:{&t.a,&t.b,&t.c}){
            v->x+=dx;v->y+=dy;v->z-=mesh.min.z;
        }
        int count=static_cast<int>(std::ceil(sz/cfg.layerHeight));
        if(count>10000)throw std::runtime_error("Too many slicing layers");
        Writer writer(cfg);writer.header();
        for(int layer=0;layer<count;layer++){
            double z=std::min((layer+0.5)*cfg.layerHeight,sz-1e-6);
            Paths outlines=sectionLoops(placed,z);
            if(outlines.empty())continue;
            orientPaths(outlines);
            writer.setLayer(layer,(layer+1)*cfg.layerHeight);
            for(int wall=0;wall<cfg.walls;wall++){
                auto perimeters=insetAll(outlines,(wall+0.5)*cfg.lineWidth);
                for(auto& p:perimeters) writer.path(p,true);
            }
            bool solid=layer<cfg.topBottomLayers||layer>=count-cfg.topBottomLayers;
            int density=solid?100:cfg.infillPercent;
            if(density>0){
                double spacing=cfg.lineWidth*100.0/density;
                // Not a full geometric clipping/offset library. Requires validation for intricate models.
                auto region=insetAll(outlines,(cfg.walls+0.5)*cfg.lineWidth);
                fill(writer,region,spacing,layer%2==0);
            }
            ++result.layers;
        }
        if(result.layers==0)throw std::runtime_error("No printable layers found");
        writer.footer();result.gcode=writer.str();result.extrusionMoves=writer.moves;
        if(writer.moves==0)throw std::runtime_error("No extrusion paths generated");
        result.ok=true;result.message="Slice created: "+std::to_string(result.layers)+" layers, "
            +std::to_string(writer.moves)+" extrusion moves";
    }catch(const std::exception& ex){result.ok=false;result.message=ex.what();result.gcode.clear();}
    return result;
}
std::vector<float> preview(const Mesh& mesh,std::size_t limit){
    const double sx=mesh.max.x-mesh.min.x,sy=mesh.max.y-mesh.min.y,sz=mesh.max.z-mesh.min.z;
    auto count=mesh.triangles.size(),step=std::max<std::size_t>(1,(count+limit-1)/limit);
    std::vector<float> out;out.reserve(4+9*std::min(count,limit));
    out.push_back(static_cast<float>(count));out.push_back(static_cast<float>(sx));
    out.push_back(static_cast<float>(sy));out.push_back(static_cast<float>(sz));
    for(std::size_t i=0;i<count;i+=step){
        const auto& t=mesh.triangles[i];
        for(auto v:{t.a,t.b,t.c}){
            out.push_back(static_cast<float>(v.x-(mesh.max.x+mesh.min.x)/2));
            out.push_back(static_cast<float>(v.y-(mesh.max.y+mesh.min.y)/2));
            out.push_back(static_cast<float>(v.z-mesh.min.z-sz/2));
        }
    }
    return out;
}
}
