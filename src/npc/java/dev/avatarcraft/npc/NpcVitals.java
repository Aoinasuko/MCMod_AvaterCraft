package dev.avatarcraft.npc;

/** Vanilla food/saturation/exhaustion and regeneration rules, independent of ServerPlayer. */
public final class NpcVitals {
  public record Effect(float healing, boolean starvation) {}

  private int food = 20, timer;
  private float saturation = 5, exhaustion;

  public int food() {
    return food;
  }

  public float saturation() {
    return saturation;
  }

  public float exhaustion() {
    return exhaustion;
  }

  public int timer() {
    return timer;
  }

  public void restore(int food, float saturation, float exhaustion, int timer) {
    this.food = Math.clamp(food, 0, 20);
    this.saturation = Float.isFinite(saturation) ? Math.clamp(saturation, 0, this.food) : 0;
    this.exhaustion = Float.isFinite(exhaustion) ? Math.clamp(exhaustion, 0, 40) : 0;
    this.timer = Math.clamp(timer, 0, 79);
  }

  public void exhaust(float amount) {
    if (Float.isFinite(amount) && amount > 0) exhaustion = Math.min(40, exhaustion + amount);
  }

  public void eat(int nutrition, float saturation) {
    food = Math.clamp(food + nutrition, 0, 20);
    this.saturation = Math.clamp(this.saturation + saturation, 0, food);
  }

  public Effect tick(float health, float maximum, int difficulty, boolean naturalRegen) {
    if (exhaustion > 4) {
      exhaustion -= 4;
      if (saturation > 0) saturation = Math.max(0, saturation - 1);
      else if (difficulty != 0) food = Math.max(0, food - 1);
    }
    boolean hurt = health > 0 && health < maximum;
    if (naturalRegen && saturation > 0 && hurt && food >= 20) {
      if (++timer >= 10) {
        float spent = Math.min(saturation, 6);
        exhaust(spent);
        timer = 0;
        return new Effect(spent / 6, false);
      }
    } else if (naturalRegen && food >= 18 && hurt) {
      if (++timer >= 80) {
        exhaust(6);
        timer = 0;
        return new Effect(1, false);
      }
    } else if (food == 0) {
      if (++timer >= 80) {
        timer = 0;
        return new Effect(0, health > 10 || difficulty == 3 || health > 1 && difficulty == 2);
      }
    } else timer = 0;
    return new Effect(0, false);
  }
}
