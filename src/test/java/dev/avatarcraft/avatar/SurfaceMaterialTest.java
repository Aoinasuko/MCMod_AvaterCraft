package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SurfaceMaterialTest {
  static byte[] image(int left, int right) throws IOException {
    var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) image.setRGB(x, y, x < 4 ? left : right);
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void blendModesRetainHdrAndMasksSelectTheContribution() {
    assertEquals(.6f, SurfaceMaterial.blend(.2f, 1, .5f, 0), 1e-6);
    assertEquals(1.4f, SurfaceMaterial.blend(.8f, .6f, 1, 1), 1e-6);
    assertEquals(.6f, SurfaceMaterial.blend(.2f, .5f, 1, 2), 1e-6);
    assertEquals(.1f, SurfaceMaterial.blend(.2f, .5f, 1, 3), 1e-6);
    for (int mode = 0; mode < 4; mode++)
      assertEquals(.2f, SurfaceMaterial.blend(.2f, .5f, 0, mode));
    assertEquals(.25f, SurfaceMaterial.layerAlpha(.5f, .5f, 2));
    assertEquals(1, SurfaceMaterial.layerAlpha(.8f, .5f, 3));
  }

  @Test
  void atlasKeepsTransparentPixelsAndPaddedNeighboursIsolated() throws Exception {
    var atlas =
        SurfaceAtlas.build(
            Map.of("red", image(0xffff0000, 0x00ff0000), "blue", image(0xff0000ff, 0xff0000ff)));
    var r = atlas.rects().get("red");
    var b = atlas.rects().get("blue");
    assertEquals(0xffff0000, atlas.pixels().getRGB(r.x(), r.y()));
    assertEquals(0xffff0000, atlas.pixels().getRGB(r.x() - 1, r.y() - 1));
    assertEquals(0x00ff0000, atlas.pixels().getRGB(r.x() + r.width(), r.y()));
    assertEquals(0xff0000ff, atlas.pixels().getRGB(b.x() - 1, b.y()));
    assertFalse(atlas.reduced());
  }

  @Test
  void atlasReducesOversizedImagesWithinTheBound() throws Exception {
    var large = new BufferedImage(4200, 4, BufferedImage.TYPE_INT_ARGB);
    var png = new ByteArrayOutputStream();
    ImageIO.write(large, "png", png);
    var atlas = SurfaceAtlas.build(Map.of("large", png.toByteArray()));
    assertTrue(atlas.reduced());
    assertTrue(atlas.pixels().getWidth() <= 4096);
    assertEquals(2100, atlas.rects().get("large").width());
    assertThrows(
        IOException.class, () -> SurfaceAtlas.build(Map.of("invalid", new byte[] {1, 2, 3})));
  }

  @Test
  void absentTexturesAndLegacyMaterialsHaveSafeDefaults() throws Exception {
    var mat = new Avatar.Material();
    var atlas = SurfaceAtlas.build(Map.of());
    assertTrue(SurfaceAtlas.uniforms(mat, atlas).present().isEmpty());
    assertEquals(0f, SurfaceMaterial.defines(mat).get("S_USEBUMPMAP"));
    assertEquals(1f, SurfaceMaterial.defines(mat).get("S_MAINTEXHSVG_W"));
    var source =
        Path.of(
            "unity/Packages/dev.avatarcraft.exporter/Editor/Resources/AvatarCraft/surface.json");
    assertArrayEquals(
        Files.readAllBytes(source),
        Files.readAllBytes(Path.of("src/main/resources/assets/bep_3davater_loader/surface.json")));
  }

  private record Fixture(
      Avatar.Material material, SurfaceAtlas.Uniforms atlas, Map<String, Float> defines) {}

  @Test
  void produceGpuFixturesUsingTheRuntimeAtlasAndUniforms() throws Exception {
    var root = Path.of("build/surface-qa");
    Files.createDirectories(root);
    var sources =
        Map.of(
            "red",
            image(0xffff0000, 0xffff0000),
            "blue",
            image(0xff0000ff, 0xff0000ff),
            "green",
            image(0xff00ff00, 0xff00ff00),
            "mask",
            image(0xff000000, 0xffffffff),
            "normal",
            image(0xffe680cc, 0xffe680cc));
    var atlas = SurfaceAtlas.build(sources);
    ImageIO.write(atlas.pixels(), "png", root.resolve("atlas.png").toFile());
    var fixtures = new LinkedHashMap<String, Fixture>();
    add(fixtures, "empty", new Avatar.Material(), SurfaceAtlas.build(Map.of()));
    var base = new Avatar.Material();
    texture(base, "_Main2ndTex", "red");
    texture(base, "_Main2ndBlendMask", "mask");
    texture(base, "_Main3rdTex", "blue");
    texture(base, "_Main3rdBlendMask", "mask");
    texture(base, "_BumpMap", "normal");
    texture(base, "_Bump2ndMap", "normal");
    texture(base, "_Bump2ndScaleMask", "mask");
    texture(base, "_MatCapTex", "green");
    texture(base, "_MatCapBlendMask", "mask");
    texture(base, "_MatCap2ndTex", "blue");
    texture(base, "_MatCap2ndBlendMask", "mask");
    texture(base, "_EmissionMap", "red");
    texture(base, "_EmissionBlendMask", "mask");
    texture(base, "_Emission2ndMap", "blue");
    texture(base, "_Emission2ndBlendMask", "mask");
    texture(base, "_ShadowStrengthMask", "mask");
    texture(base, "_RimColorTex", "mask");
    texture(base, "_DissolveMask", "mask");
    texture(base, "_AlphaMask", "mask");
    texture(base, "_OutlineWidthMask", "mask");
    add(fixtures, "baseline", base, atlas);
    change(fixtures, "layer2", base, atlas, Map.of("_UseMain2ndTex", 1));
    change(fixtures, "layer3", base, atlas, Map.of("_UseMain3rdTex", 1, "_Main3rdTexBlendMode", 1));
    change(fixtures, "matcap1", base, atlas, Map.of("_UseMatCap", 1, "_MatCapBlendMode", 0));
    change(fixtures, "matcap2", base, atlas, Map.of("_UseMatCap2nd", 1, "_MatCap2ndBlendMode", 0));
    change(fixtures, "emission1", base, atlas, Map.of("_UseEmission", 1));
    change(fixtures, "emission2", base, atlas, Map.of("_UseEmission2nd", 1));
    change(fixtures, "shadow", base, atlas, Map.of("_UseShadow", 1, "_ShadowBorder", .8));
    change(
        fixtures,
        "normal1",
        base,
        atlas,
        Map.of("_UseShadow", 1, "_ShadowBorder", .8, "_UseBumpMap", 1));
    change(
        fixtures,
        "normal2",
        base,
        atlas,
        Map.of("_UseShadow", 1, "_ShadowBorder", .8, "_UseBump2ndMap", 1));
    change(fixtures, "rim", base, atlas, Map.of("_UseRim", 1, "_RimBorder", -.2));
    var dissolve = copy(base);
    dissolve.properties.add("_DissolveParams", JsonParser.parseString("[1,0,0.5,0.1]"));
    add(fixtures, "dissolve", dissolve, atlas);
    add(fixtures, "alpha", base, atlas);
    add(fixtures, "outline", base, atlas);
    Files.writeString(root.resolve("fixtures.json"), AvatarArchive.GSON.toJson(fixtures));
    assertEquals(15, fixtures.size());
  }

  static Avatar.Material copy(Avatar.Material mat) {
    return AvatarArchive.GSON.fromJson(AvatarArchive.GSON.toJson(mat), Avatar.Material.class);
  }

  static void texture(Avatar.Material mat, String slot, String entry) {
    var value = new JsonObject();
    value.addProperty("texture", entry);
    value.add("scale", JsonParser.parseString("[1,1]"));
    value.add("offset", JsonParser.parseString("[0,0]"));
    mat.properties.add(slot, value);
  }

  static void change(
      Map<String, Fixture> fixtures,
      String name,
      Avatar.Material base,
      SurfaceAtlas.Image atlas,
      Map<String, Number> changes) {
    var mat = copy(base);
    changes.forEach((k, v) -> mat.properties.addProperty(k, v));
    add(fixtures, name, mat, atlas);
  }

  static void add(
      Map<String, Fixture> fixtures, String name, Avatar.Material mat, SurfaceAtlas.Image atlas) {
    fixtures.put(
        name, new Fixture(mat, SurfaceAtlas.uniforms(mat, atlas), SurfaceMaterial.defines(mat)));
  }
}
