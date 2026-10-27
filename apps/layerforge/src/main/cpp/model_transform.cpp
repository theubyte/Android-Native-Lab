#include "slicer.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>
namespace layerforge {
namespace {
constexpr double pi=3.14159265358979323846;
void check(double x){if(!std::isfinite(x))throw std::runtime_error("Invalid nonfinite transform");}
void bounds(Mesh& m){
 if(m.triangles.empty())throw std::runtime_error("Empty mesh");
 m.min={1e100,1e100,1e100};m.max={-1e100,-1e100,-1e100};
 for(auto& t:m.triangles)for(auto v:{t.a,t.b,t.c}){
  for(double c:{v.x,v.y,v.z})check(c);
  m.min.x=std::min(m.min.x,v.x);m.min.y=std::min(m.min.y,v.y);m.min.z=std::min(m.min.z,v.z);
  m.max.x=std::max(m.max.x,v.x);m.max.y=std::max(m.max.y,v.y);m.max.z=std::max(m.max.z,v.z);
 }
}
}
Mesh transformMesh(const Mesh& input,const ModelTransform& t){
 for(double v:{t.scale,t.rotationX,t.rotationY,t.rotationZ,t.offsetX,t.offsetY})check(v);
 if(t.scale<0.01||t.scale>10.0)throw std::runtime_error("Scale must be between 0.01 and 10");
 for(double r:{t.rotationX,t.rotationY,t.rotationZ})if(std::abs(r)>360)throw std::runtime_error("Rotation out of bounds");
 Mesh m=input;
 double cx=(m.min.x+m.max.x)*0.5,cy=(m.min.y+m.max.y)*0.5,cz=(m.min.z+m.max.z)*0.5;
 double rx=t.rotationX*pi/180,ry=t.rotationY*pi/180,rz=t.rotationZ*pi/180;
 double ax=std::cos(rx),bx=std::sin(rx),ay=std::cos(ry),by=std::sin(ry),az=std::cos(rz),bz=std::sin(rz);
 for(auto& tri:m.triangles)for(auto* v:{&tri.a,&tri.b,&tri.c}){
  double x=(v->x-cx)*t.scale,y=(v->y-cy)*t.scale,z=(v->z-cz)*t.scale;
  double y1=ax*y-bx*z,z1=bx*y+ax*z;
  double x2=ay*x+by*z1,z2=-by*x+ay*z1;
  v->x=az*x2-bz*y1;v->y=bz*x2+az*y1;v->z=z2;
 }
 bounds(m);
 double dx=t.offsetX-(m.min.x+m.max.x)*0.5,dy=t.offsetY-(m.min.y+m.max.y)*0.5,dz=-m.min.z;
 for(auto& tri:m.triangles)for(auto* v:{&tri.a,&tri.b,&tri.c}){v->x+=dx;v->y+=dy;v->z+=dz;}
 bounds(m);
 return m;
}
std::vector<float> previewTransformed(const Mesh& m,const ModelTransform& t,std::size_t n){return preview(transformMesh(m,t),n);}
}
