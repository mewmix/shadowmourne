package com.mewmix.glaive.core

import android.content.Context
import android.content.SharedPreferences
import com.mewmix.glaive.data.GlaiveItem
import java.io.File

object FavoritesManager {
    private const val PREF_NAME = "glaive_favorites"
    private const val KEY_FAVORITES = "favorite_paths"

    fun addFavorite(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val favorites = getFavoritesPaths(prefs).toMutableList()
        
        if (!favorites.contains(path)) {
            favorites.add(0, path)
            saveFavoritesOrdered(prefs, favorites)
        }
    }

    fun removeFavorite(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val favorites = getFavoritesPaths(prefs).toMutableList()
        
        if (favorites.remove(path)) {
            saveFavoritesOrdered(prefs, favorites)
        }
    }

    fun isFavorite(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return getFavoritesPaths(prefs).contains(path)
    }

    fun getFavorites(context: Context): List<GlaiveItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val paths = getFavoritesPaths(prefs)
        
        val items = paths.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) {
                GlaiveItem(
                    name = file.name,
                    path = file.absolutePath,
                    type = if (file.isDirectory) GlaiveItem.TYPE_DIR else GlaiveItem.TYPE_FILE,
                    size = file.length(),
                    mtime = file.lastModified()
                )
            } else {
                null
            }
        }.toMutableList()

        // Inject Recycle Bin at the top
        val recycleBinPath = RecycleBinManager.getTrashPath()
        items.add(0, GlaiveItem(
            name = "Recycle Bin",
            path = recycleBinPath,
            type = GlaiveItem.TYPE_DIR,
            size = 0,
            mtime = System.currentTimeMillis()
        ))

        return items
    }

    private fun saveFavoritesOrdered(prefs: SharedPreferences, paths: List<String>) {
        prefs.edit().putString("favorite_paths_ordered", paths.joinToString("|")).apply()
    }

    private fun getFavoritesPaths(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString("favorite_paths_ordered", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|")
    }
}
