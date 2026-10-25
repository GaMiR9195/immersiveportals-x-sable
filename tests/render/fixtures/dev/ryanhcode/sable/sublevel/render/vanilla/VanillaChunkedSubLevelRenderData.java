package dev.ryanhcode.sable.sublevel.render.vanilla;
public class VanillaChunkedSubLevelRenderData {
 public final java.util.List<Section> sections=new java.util.ArrayList<>();public java.util.List<Section> allRenderSections(){return sections;}
 public static class Section {public final Compiled compiled=new Compiled();public Compiled getCompiled(){return compiled;} }
 public static class Compiled {public final java.util.List<Object> bes=new java.util.ArrayList<>();public java.util.List<Object> getRenderableBlockEntities(){return bes;} }
}
