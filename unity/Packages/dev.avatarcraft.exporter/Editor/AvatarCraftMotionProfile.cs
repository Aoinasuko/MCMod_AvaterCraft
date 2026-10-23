using System;
using System.Collections.Generic;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace AvatarCraft.Editor
{
    [Serializable]
    public sealed class AvatarCraftMotionSlot
    {
        public string action, builtin;
        public AnimationClip customClip;
        public float speed = 1, blendSeconds = .18f;
    }

    public sealed class AvatarCraftMotionProfile : ScriptableObject
    {
        public Avatar sourceAvatar;
        public string avatarName;
        public List<AvatarCraftMotionSlot> motions = new List<AvatarCraftMotionSlot>();

        public static float DefaultBlend(string action) => action == "jump" ? .06f : action == "fall" ? .08f : action == "attack" || action == "mine" ? .04f : action == "land" || action == "hurt" ? .07f : .18f;

        public void EnsureDefaults()
        {
            foreach (var action in MotionExport.Library["actions"].Children<Newtonsoft.Json.Linq.JProperty>())
                if (!motions.Any(s => s.action == action.Name))
                    motions.Add(new AvatarCraftMotionSlot { action = action.Name, builtin = (string)action.Value["clip"], blendSeconds = DefaultBlend(action.Name) });
        }

        public static AvatarCraftMotionProfile FindFor(GameObject source)
        {
            var animator = source ? source.GetComponent<Animator>() : null;
            if (!animator) return null;
            return AssetDatabase.FindAssets("t:AvatarCraftMotionProfile").Select(AssetDatabase.GUIDToAssetPath)
                .Select(AssetDatabase.LoadAssetAtPath<AvatarCraftMotionProfile>)
                .FirstOrDefault(p => p && p.sourceAvatar == animator.avatar && p.avatarName == source.name);
        }

        public static AvatarCraftMotionProfile CreateFor(GameObject source, string path)
        {
            var profile = CreateInstance<AvatarCraftMotionProfile>();
            profile.sourceAvatar = source.GetComponent<Animator>().avatar;
            profile.avatarName = source.name;
            profile.EnsureDefaults();
            AssetDatabase.CreateAsset(profile, AssetDatabase.GenerateUniqueAssetPath(path));
            AssetDatabase.SaveAssets();
            return profile;
        }
    }
}
