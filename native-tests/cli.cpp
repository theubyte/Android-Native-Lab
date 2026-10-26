#include "slicer.hpp"
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
using namespace layerforge;
int main(int argc,char** argv){
 if(argc!=3){std::cerr<<"Usage: layerforge-cli input.stl|input.obj output.gcode\n";return 2;}
 std::ifstream in(argv[1],std::ios::binary);
 if(!in){std::cerr<<"Failed to open model\n";return 2;}
 std::vector<std::uint8_t> data((std::istreambuf_iterator<char>(in)),{});
 try{
    auto r=slice(parseMesh(data.data(),data.size()),Settings{});
    if(!r.ok){std::cerr<<r.message<<"\n";return 1;}
    std::ofstream out(argv[2],std::ios::binary);
    if(!out){std::cerr<<"Failed to write output\n";return 1;}
    out<<r.gcode;out.flush();if(!out)return 1;
    std::cout<<r.message<<"\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
