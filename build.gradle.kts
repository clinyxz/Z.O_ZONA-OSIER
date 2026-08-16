// Top-level build file for ZONA-OSIER

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // ObjectBox — Vector DB on-device dengan HNSW indexing
    id("io.objectbox") version "4.0.3" apply false
}