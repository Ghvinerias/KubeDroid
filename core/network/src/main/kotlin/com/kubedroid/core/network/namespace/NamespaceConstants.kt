package com.kubedroid.core.network.namespace

const val ALL_NAMESPACES = "__all__"

fun String?.isAllNamespacesSelection(): Boolean = this?.trim() == ALL_NAMESPACES
