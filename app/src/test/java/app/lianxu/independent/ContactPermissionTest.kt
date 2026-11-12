package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
class ContactPermissionTest {
    private val o=CompanionOpportunity("followup","a",1,1,0,0,1000,10000000,"test","queued",0)
    private val r=o.copy(id="relationship:a")
    @Test fun promisedIgnoresThreshold(){assertEquals(ContactPermission.Allowed,ContactPermission.evaluate(o,1000,1,false,0){error("threshold consulted")})}
    @Test fun promisedIgnoresMissingForecast(){assertEquals(ContactPermission.Allowed,ContactPermission.evaluate(o,5000,1,false,0){null})}
    @Test fun promisedDailyLimitDefers(){assertEquals(ContactPermission.Deferred(86400000),ContactPermission.evaluate(o.copy(expiresAt=172800000),1000,1,true,0){null})}
    @Test fun promisedDailyLimitMinimumDelay(){assertEquals(ContactPermission.Deferred(86400000+900000),ContactPermission.evaluate(o.copy(expiresAt=172800000),86400000,1,true,0){null})}
    @Test fun promisedDailyLimitCanDeferPastLegacyExpiry(){assertEquals(ContactPermission.Deferred(86400000),ContactPermission.evaluate(o,1000,1,true,0){null})}
    @Test fun promisedZeroDailyMaxCancels(){assertEquals(ContactPermission.Denied,ContactPermission.evaluate(o,1000,0,false,0){null})}
    @Test fun relationshipBelowThresholdDenied(){assertEquals(ContactPermission.Denied,ContactPermission.evaluate(r,1000,1,false,0){2000})}
    @Test fun relationshipCrossingAllows(){assertEquals(ContactPermission.Allowed,ContactPermission.evaluate(r,2000,1,false,0){2000})}
    @Test fun relationshipMissingForecastDenied(){assertEquals(ContactPermission.Denied,ContactPermission.evaluate(r,2000,1,false,0){null})}
    @Test fun relationshipDailyLimitDenied(){assertEquals(ContactPermission.Denied,ContactPermission.evaluate(r,2000,1,true,0){2000})}
}
