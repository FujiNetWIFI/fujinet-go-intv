package online.fujinet.go.intv.input

/**
 * Constants mirroring fujinet-go-intv-desktop's core/include/intvsession.h
 * enums, so Kotlin and the native JNI bridge (EmulatorNative) agree on the
 * wire values without either side owning a generated header.
 */
object Intv {
    // intvsession_pad_side
    const val PAD_LEFT = 0
    const val PAD_RIGHT = 1
    const val PAD_ECS_LEFT = 2
    const val PAD_ECS_RIGHT = 3

    // intvsession_key
    const val KEY_0 = 0
    const val KEY_1 = 1
    const val KEY_2 = 2
    const val KEY_3 = 3
    const val KEY_4 = 4
    const val KEY_5 = 5
    const val KEY_6 = 6
    const val KEY_7 = 7
    const val KEY_8 = 8
    const val KEY_9 = 9
    const val KEY_CLEAR = 10
    const val KEY_ENTER = 11
    const val ACTION_TOP = 12
    const val ACTION_LOWER_LEFT = 13
    const val ACTION_LOWER_RIGHT = 14

    /** No disc direction is pressed; centers the disc. */
    const val DISC_NONE = -1

    // intvsession_ecs_key -- transposed verbatim from
    // core/jzintv/intv_host.h's intv_ecs_key (the ECS 7x8 scan matrix).
    const val ECS_KEY_LEFT = 0
    const val ECS_KEY_PERIOD = 1
    const val ECS_KEY_SEMI = 2
    const val ECS_KEY_P = 3
    const val ECS_KEY_ESC = 4
    const val ECS_KEY_0 = 5
    const val ECS_KEY_ENTER = 6
    const val ECS_KEY_COMMA = 7
    const val ECS_KEY_M = 8
    const val ECS_KEY_K = 9
    const val ECS_KEY_I = 10
    const val ECS_KEY_9 = 11
    const val ECS_KEY_8 = 12
    const val ECS_KEY_O = 13
    const val ECS_KEY_L = 14
    const val ECS_KEY_N = 15
    const val ECS_KEY_B = 16
    const val ECS_KEY_H = 17
    const val ECS_KEY_Y = 18
    const val ECS_KEY_7 = 19
    const val ECS_KEY_6 = 20
    const val ECS_KEY_U = 21
    const val ECS_KEY_J = 22
    const val ECS_KEY_V = 23
    const val ECS_KEY_C = 24
    const val ECS_KEY_F = 25
    const val ECS_KEY_R = 26
    const val ECS_KEY_5 = 27
    const val ECS_KEY_4 = 28
    const val ECS_KEY_T = 29
    const val ECS_KEY_G = 30
    const val ECS_KEY_X = 31
    const val ECS_KEY_Z = 32
    const val ECS_KEY_S = 33
    const val ECS_KEY_W = 34
    const val ECS_KEY_3 = 35
    const val ECS_KEY_2 = 36
    const val ECS_KEY_E = 37
    const val ECS_KEY_D = 38
    const val ECS_KEY_SPACE = 39
    const val ECS_KEY_DOWN = 40
    const val ECS_KEY_UP = 41
    const val ECS_KEY_Q = 42
    const val ECS_KEY_1 = 43
    const val ECS_KEY_RIGHT = 44
    const val ECS_KEY_CTRL = 45
    const val ECS_KEY_A = 46
    const val ECS_KEY_SHIFT = 47

    // intvsession_start_opts hw/video fields (INTVSESSION_HW_* / _VIDEO_*)
    const val HW_AUTO = 0
    const val HW_OFF = 1
    const val HW_ON = 2

    const val VIDEO_NTSC = 0
    const val VIDEO_PAL = 1

    /** exec.bin / grom.bin / ecs.bin exact sizes (core/jzintv/intv_host.c). */
    const val EXEC_SIZE = 8192
    const val GROM_SIZE = 2048
    const val ECS_SIZE = 12 * 1024 * 2
}
