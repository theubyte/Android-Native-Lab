#include "slicer.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <cstring>
#include <vector>
#include <sstream>
#include <string>
using namespace layerforge;
std::string cube(double extent){
  Vec3 a{0,0,0},b{extent,0,0},c{extent,extent,0},d{0,extent,0};
  Vec3 e{0,0,extent},f{extent,0,extent},g{extent,extent,extent},h{0,extent,extent};
  std::ostringstream os;os<<"solid cube\n";
  auto tri=[&](Vec3 x,Vec3 y,Vec3 z){
    os<<"facet normal 0 0 1\nouter loop\n";
    for(auto p:{x,y,z})os<<"vertex "<<p.x<<" "<<p.y<<" "<<p.z<<"\n";
    os<<"endloop\nendfacet\n";
  };
  tri(a,c,b);tri(a,d,c);tri(e,f,g);tri(e,g,h);tri(a,b,f);tri(a,f,e);
  tri(b,c,g);tri(b,g,f);tri(c,d,h);tri(c,h,g);tri(d,a,e);tri(d,e,h);
  os<<"endsolid\n";return os.str();
}
int main(){
 auto stl=cube(10.0);auto mesh=parseStl((const std::uint8_t*)stl.data(),stl.size());
 assert(mesh.triangles.size()==12);
 assert(std::abs(mesh.max.x-mesh.min.x-10.0)<1e-6);
 auto pp=preview(mesh);assert(pp.size()==4+12*9);
 Settings cfg;cfg.layerHeight=0.2;cfg.walls=2;cfg.infillPercent=20;
 auto result=slice(mesh,cfg);
 if(!result.ok)std::cerr<<result.message<<"\n";
 assert(result.ok);assert(result.layers==50);assert(result.extrusionMoves>100);
 assert(result.gcode.find(";LAYER:49")!=std::string::npos);
 assert(result.gcode.find("G1 X")!=std::string::npos);
 assert(result.gcode.find("M83")!=std::string::npos);
 Settings tooSmall=cfg;tooSmall.bedWidth=20; // should fit 10 mm cube with 1 mm edge margin
 assert(slice(mesh,tooSmall).ok);
 tooSmall.bedWidth=21;auto giant=cube(22);
 auto giantMesh=parseStl((const std::uint8_t*)giant.data(),giant.size());
 assert(!slice(giantMesh,tooSmall).ok);
 // Verify binary STL as well as ASCII, without depending on any third-party fixture.
 std::vector<std::uint8_t> binary(84+12*50,0);
 binary[80]=12;
 auto writeFloat=[&](std::size_t offset, float value){
   std::uint32_t bits;std::memcpy(&bits,&value,4);
   for(int b=0;b<4;b++)binary[offset+b]=static_cast<std::uint8_t>(bits>>(b*8));
 };
 for(std::size_t i=0;i<12;i++){
    const auto& t=mesh.triangles[i];
    int v=0;
    for(auto p:{t.a,t.b,t.c}){
      for(double c:{p.x,p.y,p.z})writeFloat(84+i*50+12+4*v++,static_cast<float>(c));
    }
 }
 auto binMesh=parseStl(binary.data(),binary.size());
 assert(binMesh.triangles.size()==12);assert(slice(binMesh,cfg).ok);
 // Assertions about exported coordinates and positive extrusion.
 std::istringstream lines(result.gcode);std::string line;
 int positive=0;
 while(std::getline(lines,line)){
    if(line.rfind("G1 X",0)!=0)continue;
    auto position=line.find(" E");assert(position!=std::string::npos);
    auto amount=std::stod(line.substr(position+2));assert(amount>0);positive++;
 }
 assert(positive==static_cast<int>(result.extrusionMoves));
 auto invalid=std::string("solid bad\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendsolid\n");
 auto badMesh=parseStl((const std::uint8_t*)invalid.data(),invalid.size());
 assert(!slice(badMesh,cfg).ok);
 std::cout<<"PASS native slicer: 12 triangles, 50 layers, "<<result.extrusionMoves<<" extrusion moves\n";
}
