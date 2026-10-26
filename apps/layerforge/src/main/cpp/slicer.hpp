#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace layerforge {
struct Vec3 { double x=0, y=0, z=0; };
struct Vec2 { double x=0, y=0; };
struct Triangle { Vec3 a,b,c; };
struct Mesh {
    std::vector<Triangle> triangles;
    Vec3 min, max;
};
struct Settings {
    double layerHeight=0.20, lineWidth=0.45, printSpeed=50.0, travelSpeed=150.0;
    double bedWidth=220, bedDepth=220, bedHeight=250, filamentDiameter=1.75;
    double retractionMm=0.8, retractMinTravelMm=2.0;
    int fanPercent=80;
    int walls=2, infillPercent=15, topBottomLayers=4, nozzleC=210, bedC=60;
};
struct Result {
    bool ok=false;
    std::string message;
    std::string gcode;
    int layers=0;
    std::size_t extrusionMoves=0;
};
Mesh parseStl(const std::uint8_t* data, std::size_t size);
Mesh parseMesh(const std::uint8_t* data, std::size_t size); // STL or Wavefront OBJ
Result slice(const Mesh& mesh, const Settings& settings);
std::vector<float> preview(const Mesh& mesh, std::size_t triangleLimit=2000);
}
