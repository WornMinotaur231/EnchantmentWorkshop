package cn.mc.enchantmentworkshop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProgramRuleTest {
 @Test void emptyConditionsAreUnconditionalForAllAndAny() throws Exception {
     assertTrue(ProgramRule.parse(ConfigLoader.yaml("conditions: {}\ncondition-mode: ALL")).matches(null,null));
     assertTrue(ProgramRule.parse(ConfigLoader.yaml("conditions: {}\ncondition-mode: ANY")).matches(null,null));
 }
 @Test void rejectsMissingOtherContext() throws Exception {assertThrows(IllegalArgumentException.class,()->ProgramRule.parse(ConfigLoader.yaml("trigger: PERIODIC\ntarget: OTHER\naction: POTION")));}
 @Test void rejectsUnknownTrigger() throws Exception {assertThrows(IllegalArgumentException.class,()->ProgramRule.parse(ConfigLoader.yaml("trigger: TYPO")));}
 @Test void validatesRanges(){assertThrows(IllegalArgumentException.class,()->ProgramRule.validate("box","10,0,0,1,10,10"));assertThrows(IllegalArgumentException.class,()->ProgramRule.validate("height","NaN,10"));ProgramRule.validate("height","-64,320");}
 @Test void validatesWeatherAndState(){assertThrows(IllegalArgumentException.class,()->ProgramRule.validate("weather","snow"));assertThrows(IllegalArgumentException.class,()->ProgramRule.validate("sneaking","yes"));}
 @Test void readsCompoundConditions() throws Exception {var r=ProgramRule.parse(ConfigLoader.yaml("trigger: HURT\ntarget: OTHER\naction: POTION\ncondition-mode: ANY\nconditions:\n  weather: RAIN\n  sneaking: true"));assertTrue(r.any());assertEquals(2,r.conditions().size());}
}
