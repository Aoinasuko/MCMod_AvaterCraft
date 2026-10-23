package dev.avatarcraft.avatar;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaterialAlphaTest {
  @Test
  void transparentMaterialsKeepLowOpacityAndHonorDepthWrite() {
    var m = new Avatar.Material();
    m.shader = "Hidden/lilToonTransparent";
    m.renderQueue = 3000;
    m.properties.addProperty("_Cutoff", .5f);
    assertEquals(MaterialAlpha.Mode.TRANSLUCENT, MaterialAlpha.mode(m));
    assertTrue(MaterialAlpha.cutoff(m) < .02f);
    assertFalse(MaterialAlpha.writeDepth(m));
    m.properties.addProperty("_ZWrite", 1);
    assertTrue(MaterialAlpha.writeDepth(m));
  }

  @Test
  void cutoutAndOpaqueModesDoNotBecomeBlendedBasedOnColorAlphaAlone() {
    var m = new Avatar.Material();
    m.shader = "lilToon";
    m.renderType = "Opaque";
    m.renderQueue = 2000;
    assertEquals(MaterialAlpha.Mode.OPAQUE, MaterialAlpha.mode(m));
    assertTrue(MaterialAlpha.writeDepth(m));
    m.renderType = "TransparentCutout";
    m.properties.addProperty("_Cutoff", .3f);
    assertEquals(MaterialAlpha.Mode.CUTOUT, MaterialAlpha.mode(m));
    assertEquals(.3f, MaterialAlpha.cutoff(m));
    m.renderType = null;
    m.properties.addProperty("_TransparentMode", 2);
    assertEquals(MaterialAlpha.Mode.TRANSLUCENT, MaterialAlpha.mode(m));
  }

  @Test
  void alphaMaskModesMatchUnityReplaceMultiplyAddSubtractAndClamp() {
    assertEquals(.5f, MaterialAlpha.mask(.2f, .5f, 1, 1, 0));
    assertEquals(.1f, MaterialAlpha.mask(.2f, .5f, 2, 1, 0));
    assertEquals(.7f, MaterialAlpha.mask(.2f, .5f, 3, 1, 0));
    assertEquals(0, MaterialAlpha.mask(.2f, .5f, 4, 1, 0));
    assertEquals(1, MaterialAlpha.mask(.8f, .8f, 3, 2, 0));
    assertEquals(.2f, MaterialAlpha.mask(.2f, .5f, 0, 1, 0));
  }
}
