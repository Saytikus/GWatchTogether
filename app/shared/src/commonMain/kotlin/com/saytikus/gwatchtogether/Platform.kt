package com.saytikus.gwatchtogether

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform