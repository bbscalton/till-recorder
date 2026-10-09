package com.tillrecorder.agent

/** Which of the tablet's own cameras the watch page's camera uses. Front is the default. */
object CameraFacing {
    const val LENS_FRONT = 0
    const val LENS_BACK = 1

    /**
     * Index in [facings] (the lens facing of each camera, in CameraManager order) to open.
     * The chosen side is used when the tablet has it; otherwise the other one, so a tablet with a
     * single camera still works. Null only when there is no usable camera.
     */
    fun pick(facings: List<Int?>, back: Boolean): Int? {
        val wanted = if (back) LENS_BACK else LENS_FRONT
        val other = if (back) LENS_FRONT else LENS_BACK
        val first = facings.indexOfFirst { it == wanted }
        if (first >= 0) return first
        val fallback = facings.indexOfFirst { it == other }
        return if (fallback >= 0) fallback else null
    }
}