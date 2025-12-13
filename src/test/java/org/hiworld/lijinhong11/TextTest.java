package org.hiworld.lijinhong11;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TextTest {
    @BeforeAll
    public static void init() {
        BadWordsFilterLib.init();
    }

    @Test
    public void badWords() {
        PlayerInfo example = new PlayerInfo("2b2t", "catdot");
        boolean b = BadWordsFilterLib.isMatch(example, "习近平");
        System.out.println(b);
    }
}
