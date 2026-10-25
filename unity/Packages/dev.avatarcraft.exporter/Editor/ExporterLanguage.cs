using System.Globalization;
using UnityEditor;

namespace AvatarCraft.Editor
{
    public static class ExporterLanguage
    {
        const string Preference = "BEP.3DAvater.Exporter.Language";
        public static bool English
        {
            get => EditorPrefs.GetInt(Preference, 0) == 1;
            set => EditorPrefs.SetInt(Preference, value ? 1 : 0);
        }
        public static string T(string japanese, string english) => English ? english : japanese;
        public static string MotionName(string id, string japanese) => English
            ? CultureInfo.InvariantCulture.TextInfo.ToTitleCase(id.Replace('_', ' ')) : japanese;
    }
}
