package com.tacnav

import org.json.JSONObject

// Fixed local style for the Protomaps v4 schema; map packs cannot inject URLs or styles.
fun vectorStyle(tileUrl: String, manifest: MapManifest): String = JSONObject("""
{
  "version":8,
  "glyphs":"asset://fonts/{fontstack}/{range}.pbf",
  "sources":{"imagery":{"type":"vector"}},
  "layers":[
    {"id":"background","type":"background","paint":{"background-color":"#152c3b"}},
    {"id":"earth","type":"fill","source":"imagery","source-layer":"earth","paint":{"fill-color":"#303c32"}},
    {"id":"landcover","type":"fill","source":"imagery","source-layer":"landcover","paint":{"fill-color":["match",["get","kind"],"forest","#263d30","urban_area","#494940","farmland","#424735","#354332"]}},
    {"id":"green","type":"fill","source":"imagery","source-layer":"landuse","filter":["in","kind","forest","wood","park","national_park","nature_reserve","grass"],"paint":{"fill-color":"#263d30"}},
    {"id":"water","type":"fill","source":"imagery","source-layer":"water","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":"#152c3b"}},
    {"id":"waterways","type":"line","source":"imagery","source-layer":"water","filter":["==",["geometry-type"],"LineString"],"paint":{"line-color":"#326078","line-width":1}},
    {"id":"buildings","type":"fill","source":"imagery","source-layer":"buildings","minzoom":12,"paint":{"fill-color":"#596055","fill-opacity":0.6}},
    {"id":"roads","type":"line","source":"imagery","source-layer":"roads","minzoom":5,"filter":["==",["geometry-type"],"LineString"],"paint":{"line-color":["match",["get","kind"],"highway","#dbbc73","major_road","#b6b096","#7e887e"],"line-width":["interpolate",["linear"],["zoom"],5,0.5,10,1,13,2,18,5]}},
    {"id":"boundaries","type":"line","source":"imagery","source-layer":"boundaries","paint":{"line-color":"#b29eaf","line-width":1,"line-dasharray":[3,3]}},
    {"id":"road-labels","type":"symbol","source":"imagery","source-layer":"roads","minzoom":12,"layout":{"symbol-placement":"line","text-field":["coalesce",["get","name:ko"],["get","name"],["get","name:en"],""],"text-font":["Noto Sans Regular"],"text-size":11},"paint":{"text-color":"#d2d9cb","text-halo-color":"#202c26","text-halo-width":1.5}},
    {"id":"places","type":"symbol","source":"imagery","source-layer":"places","filter":["<=",["coalesce",["get","min_zoom"],0],["zoom"]],"layout":{"text-field":["coalesce",["get","name:ko"],["get","name"],["get","name:en"],""],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],3,11,10,15,16,18],"text-padding":8},"paint":{"text-color":"#eef1df","text-halo-color":"#202c26","text-halo-width":2}}
  ]
}
""").apply {
    getJSONObject("sources").getJSONObject("imagery")
        .put("url",tileUrl)
        .put("minzoom",manifest.minZoom).put("maxzoom",manifest.maxZoom)
        .put("bounds",org.json.JSONArray(manifest.bounds))
}.toString()
