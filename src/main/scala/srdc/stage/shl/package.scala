package srdc.stage

import org.json4s.JValue
import org.json4s.native.JsonMethods

package object shl {
  /** Compact JSON; numbers as parsed (BigDecimal keeps the written precision), non-ASCII characters unescaped */
  def compactJson(value: JValue): String = JsonMethods.compact(JsonMethods.render(value))
}
