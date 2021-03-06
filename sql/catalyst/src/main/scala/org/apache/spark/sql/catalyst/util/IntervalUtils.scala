/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.util

import java.math.BigDecimal
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.types.Decimal
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

object IntervalUtils {

  object IntervalUnit extends Enumeration {
    type IntervalUnit = Value

    val NANOSECOND = Value(0, "nanosecond")
    val MICROSECOND = Value(1, "microsecond")
    val MILLISECOND = Value(2, "millisecond")
    val SECOND = Value(3, "second")
    val MINUTE = Value(4, "minute")
    val HOUR = Value(5, "hour")
    val DAY = Value(6, "day")
    val WEEK = Value(7, "week")
    val MONTH = Value(8, "month")
    val YEAR = Value(9, "year")
  }
  import IntervalUnit._

  def getYears(interval: CalendarInterval): Int = {
    interval.months / MONTHS_PER_YEAR
  }

  def getMillenniums(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_MILLENNIUM
  }

  def getCenturies(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_CENTURY
  }

  def getDecades(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_DECADE
  }

  def getMonths(interval: CalendarInterval): Byte = {
    (interval.months % MONTHS_PER_YEAR).toByte
  }

  def getQuarters(interval: CalendarInterval): Byte = {
    (getMonths(interval) / MONTHS_PER_QUARTER + 1).toByte
  }

  def getDays(interval: CalendarInterval): Int = {
    interval.days
  }

  def getHours(interval: CalendarInterval): Long = {
    interval.microseconds / MICROS_PER_HOUR
  }

  def getMinutes(interval: CalendarInterval): Byte = {
    ((interval.microseconds % MICROS_PER_HOUR) / MICROS_PER_MINUTE).toByte
  }

  def getMicroseconds(interval: CalendarInterval): Long = {
    interval.microseconds % MICROS_PER_MINUTE
  }

  def getSeconds(interval: CalendarInterval): Decimal = {
    Decimal(getMicroseconds(interval), 8, 6)
  }

  def getMilliseconds(interval: CalendarInterval): Decimal = {
    Decimal(getMicroseconds(interval), 8, 3)
  }

  // Returns total number of seconds with microseconds fractional part in the given interval.
  def getEpoch(interval: CalendarInterval): Decimal = {
    var result = interval.microseconds
    result += MICROS_PER_DAY * interval.days
    result += MICROS_PER_YEAR * (interval.months / MONTHS_PER_YEAR)
    result += MICROS_PER_MONTH * (interval.months % MONTHS_PER_YEAR)
    Decimal(result, 18, 6)
  }

  private def toLongWithRange(
      fieldName: IntervalUnit,
      s: String,
      minValue: Long,
      maxValue: Long): Long = {
    val result = if (s == null) 0L else s.toLong
    require(minValue <= result && result <= maxValue,
      s"$fieldName $result outside range [$minValue, $maxValue]")

    result
  }

  private val yearMonthPattern = "^([+|-])?(\\d+)-(\\d+)$".r

  /**
   * Parse YearMonth string in form: [+|-]YYYY-MM
   *
   * adapted from HiveIntervalYearMonth.valueOf
   */
  def fromYearMonthString(input: String): CalendarInterval = {
    require(input != null, "Interval year-month string must be not null")
    def toInterval(yearStr: String, monthStr: String): CalendarInterval = {
      try {
        val years = toLongWithRange(YEAR, yearStr, 0, Integer.MAX_VALUE).toInt
        val months = toLongWithRange(MONTH, monthStr, 0, 11).toInt
        val totalMonths = Math.addExact(Math.multiplyExact(years, 12), months)
        new CalendarInterval(totalMonths, 0, 0)
      } catch {
        case NonFatal(e) =>
          throw new IllegalArgumentException(
            s"Error parsing interval year-month string: ${e.getMessage}", e)
      }
    }
    assert(input.length == input.trim.length)
    input match {
      case yearMonthPattern("-", yearStr, monthStr) =>
        negate(toInterval(yearStr, monthStr))
      case yearMonthPattern(_, yearStr, monthStr) =>
        toInterval(yearStr, monthStr)
      case _ =>
        throw new IllegalArgumentException(
          s"Interval string does not match year-month format of 'y-m': $input")
    }
  }

  /**
   * Parse dayTime string in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf
   */
  def fromDayTimeString(s: String): CalendarInterval = {
    fromDayTimeString(s, DAY, SECOND)
  }

  private val dayTimePattern =
    "^([+|-])?((\\d+) )?((\\d+):)?(\\d+):(\\d+)(\\.(\\d+))?$".r

  /**
   * Parse dayTime string in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf.
   * Below interval conversion patterns are supported:
   * - DAY TO (HOUR|MINUTE|SECOND)
   * - HOUR TO (MINUTE|SECOND)
   * - MINUTE TO SECOND
   */
  def fromDayTimeString(input: String, from: IntervalUnit, to: IntervalUnit): CalendarInterval = {
    require(input != null, "Interval day-time string must be not null")
    assert(input.length == input.trim.length)
    val m = dayTimePattern.pattern.matcher(input)
    require(m.matches, s"Interval string must match day-time format of 'd h:m:s.n': $input")

    try {
      val sign = if (m.group(1) != null && m.group(1) == "-") -1 else 1
      val days = if (m.group(2) == null) {
        0
      } else {
        toLongWithRange(DAY, m.group(3), 0, Integer.MAX_VALUE).toInt
      }
      var hours: Long = 0L
      var minutes: Long = 0L
      var seconds: Long = 0L
      if (m.group(5) != null || from == MINUTE) { // 'HH:mm:ss' or 'mm:ss minute'
        hours = toLongWithRange(HOUR, m.group(5), 0, 23)
        minutes = toLongWithRange(MINUTE, m.group(6), 0, 59)
        seconds = toLongWithRange(SECOND, m.group(7), 0, 59)
      } else if (m.group(8) != null) { // 'mm:ss.nn'
        minutes = toLongWithRange(MINUTE, m.group(6), 0, 59)
        seconds = toLongWithRange(SECOND, m.group(7), 0, 59)
      } else { // 'HH:mm'
        hours = toLongWithRange(HOUR, m.group(6), 0, 23)
        minutes = toLongWithRange(SECOND, m.group(7), 0, 59)
      }
      // Hive allow nanosecond precision interval
      var secondsFraction = parseNanos(m.group(9), seconds < 0)
      to match {
        case HOUR =>
          minutes = 0
          seconds = 0
          secondsFraction = 0
        case MINUTE =>
          seconds = 0
          secondsFraction = 0
        case SECOND =>
          // No-op
        case _ =>
          throw new IllegalArgumentException(
            s"Cannot support (interval '$input' $from to $to) expression")
      }
      var micros = secondsFraction
      micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
      micros = Math.addExact(micros, Math.multiplyExact(minutes, MICROS_PER_MINUTE))
      micros = Math.addExact(micros, Math.multiplyExact(seconds, MICROS_PER_SECOND))
      new CalendarInterval(0, sign * days, sign * micros)
    } catch {
      case e: Exception =>
        throw new IllegalArgumentException(
          s"Error parsing interval day-time string: ${e.getMessage}", e)
    }
  }

  // Parses a string with nanoseconds, truncates the result and returns microseconds
  private def parseNanos(nanosStr: String, isNegative: Boolean): Long = {
    if (nanosStr != null) {
      val maxNanosLen = 9
      val alignedStr = if (nanosStr.length < maxNanosLen) {
        (nanosStr + "000000000").substring(0, maxNanosLen)
      } else nanosStr
      val nanos = toLongWithRange(NANOSECOND, alignedStr, 0L, 999999999L)
      val micros = nanos / NANOS_PER_MICROS
      if (isNegative) -micros else micros
    } else {
      0L
    }
  }

  /**
   * Gets interval duration
   *
   * @param interval The interval to get duration
   * @param targetUnit Time units of the result
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return Duration in the specified time units
   */
  def getDuration(
      interval: CalendarInterval,
      targetUnit: TimeUnit,
      daysPerMonth: Int = 31): Long = {
    val monthsDuration = Math.multiplyExact(
      daysPerMonth * MICROS_PER_DAY,
      interval.months)
    val daysDuration = Math.multiplyExact(
      MICROS_PER_DAY,
      interval.days)
    val result = Math.addExact(interval.microseconds, Math.addExact(daysDuration, monthsDuration))
    targetUnit.convert(result, TimeUnit.MICROSECONDS)
  }

  /**
   * Checks the interval is negative
   *
   * @param interval The checked interval
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return true if duration of the given interval is less than 0 otherwise false
   */
  def isNegative(interval: CalendarInterval, daysPerMonth: Int = 31): Boolean = {
    getDuration(interval, TimeUnit.MICROSECONDS, daysPerMonth) < 0
  }

  /**
   * Makes an interval from months, days and micros with the fractional part by
   * adding the month fraction to days and the days fraction to micros.
   */
  private def fromDoubles(
      monthsWithFraction: Double,
      daysWithFraction: Double,
      microsWithFraction: Double): CalendarInterval = {
    val truncatedMonths = Math.toIntExact(monthsWithFraction.toLong)
    val days = daysWithFraction + DAYS_PER_MONTH * (monthsWithFraction - truncatedMonths)
    val truncatedDays = Math.toIntExact(days.toLong)
    val micros = microsWithFraction + MICROS_PER_DAY * (days - truncatedDays)
    new CalendarInterval(truncatedMonths, truncatedDays, micros.round)
  }

  /**
   * Unary minus, return the negated the calendar interval value.
   *
   * @param interval the interval to be negated
   * @return a new calendar interval instance with all it parameters negated from the origin one.
   */
  def negate(interval: CalendarInterval): CalendarInterval = {
    new CalendarInterval(-interval.months, -interval.days, -interval.microseconds)
  }

  /**
   * Return a new calendar interval instance of the sum of two intervals.
   */
  def add(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months + right.months
    val days = left.days + right.days
    val microseconds = left.microseconds + right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the left intervals minus the right one.
   */
  def subtract(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months - right.months
    val days = left.days - right.days
    val microseconds = left.microseconds - right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  def multiply(interval: CalendarInterval, num: Double): CalendarInterval = {
    fromDoubles(num * interval.months, num * interval.days, num * interval.microseconds)
  }

  def divide(interval: CalendarInterval, num: Double): CalendarInterval = {
    if (num == 0) throw new java.lang.ArithmeticException("divide by zero")
    fromDoubles(interval.months / num, interval.days / num, interval.microseconds / num)
  }

  // `toString` implementation in CalendarInterval is the multi-units format currently.
  def toMultiUnitsString(interval: CalendarInterval): String = interval.toString

  def toSqlStandardString(interval: CalendarInterval): String = {
    val yearMonthPart = if (interval.months < 0) {
      val ma = math.abs(interval.months)
      "-" + ma / 12 + "-" + ma % 12
    } else if (interval.months > 0) {
      "+" + interval.months / 12 + "-" + interval.months % 12
    } else {
      ""
    }

    val dayPart = if (interval.days < 0) {
      interval.days.toString
    } else if (interval.days > 0) {
      "+" + interval.days
    } else {
      ""
    }

    val timePart = if (interval.microseconds != 0) {
      val sign = if (interval.microseconds > 0) "+" else "-"
      val sb = new StringBuilder(sign)
      var rest = math.abs(interval.microseconds)
      sb.append(rest / MICROS_PER_HOUR)
      sb.append(':')
      rest %= MICROS_PER_HOUR
      val minutes = rest / MICROS_PER_MINUTE;
      if (minutes < 10) {
        sb.append(0)
      }
      sb.append(minutes)
      sb.append(':')
      rest %= MICROS_PER_MINUTE
      val bd = BigDecimal.valueOf(rest, 6)
      if (bd.compareTo(new BigDecimal(10)) < 0) {
        sb.append(0)
      }
      val s = bd.stripTrailingZeros().toPlainString
      sb.append(s)
      sb.toString()
    } else {
      ""
    }

    val intervalList = Seq(yearMonthPart, dayPart, timePart).filter(_.nonEmpty)
    if (intervalList.nonEmpty) intervalList.mkString(" ") else "0"
  }

  def toIso8601String(interval: CalendarInterval): String = {
    val sb = new StringBuilder("P")

    val year = interval.months / 12
    if (year != 0) sb.append(year + "Y")
    val month = interval.months % 12
    if (month != 0) sb.append(month + "M")

    if (interval.days != 0) sb.append(interval.days + "D")

    if (interval.microseconds != 0) {
      sb.append('T')
      var rest = interval.microseconds
      val hour = rest / MICROS_PER_HOUR
      if (hour != 0) sb.append(hour + "H")
      rest %= MICROS_PER_HOUR
      val minute = rest / MICROS_PER_MINUTE
      if (minute != 0) sb.append(minute + "M")
      rest %= MICROS_PER_MINUTE
      if (rest != 0) {
        val bd = BigDecimal.valueOf(rest, 6)
        sb.append(bd.stripTrailingZeros().toPlainString + "S")
      }
    } else if (interval.days == 0 && interval.months == 0) {
      sb.append("T0S")
    }
    sb.toString()
  }

  private object ParseState extends Enumeration {
    type ParseState = Value

    val PREFIX,
        TRIM_BEFORE_SIGN,
        SIGN,
        TRIM_BEFORE_VALUE,
        VALUE,
        VALUE_FRACTIONAL_PART,
        TRIM_BEFORE_UNIT,
        UNIT_BEGIN,
        UNIT_SUFFIX,
        UNIT_END = Value
  }
  private final val intervalStr = UTF8String.fromString("interval ")
  private def unitToUtf8(unit: IntervalUnit): UTF8String = {
    UTF8String.fromString(unit.toString)
  }
  private final val yearStr = unitToUtf8(YEAR)
  private final val monthStr = unitToUtf8(MONTH)
  private final val weekStr = unitToUtf8(WEEK)
  private final val dayStr = unitToUtf8(DAY)
  private final val hourStr = unitToUtf8(HOUR)
  private final val minuteStr = unitToUtf8(MINUTE)
  private final val secondStr = unitToUtf8(SECOND)
  private final val millisStr = unitToUtf8(MILLISECOND)
  private final val microsStr = unitToUtf8(MICROSECOND)

  /**
   * A safe version of `stringToInterval`. It returns null for invalid input string.
   */
  def safeStringToInterval(input: UTF8String): CalendarInterval = {
    try {
      stringToInterval(input)
    } catch {
      case _: IllegalArgumentException => null
    }
  }

  /**
   * Converts a string to [[CalendarInterval]] case-insensitively.
   *
   * @throws IllegalArgumentException if the input string is not in valid interval format.
   */
  def stringToInterval(input: UTF8String): CalendarInterval = {
    import ParseState._
    def throwIAE(msg: String, e: Exception = null) = {
      throw new IllegalArgumentException(s"Error parsing '$input' to interval, $msg", e)
    }

    if (input == null) {
      throwIAE("interval string cannot be null")
    }
    // scalastyle:off caselocale .toLowerCase
    val s = input.trim.toLowerCase
    // scalastyle:on
    val bytes = s.getBytes
    if (bytes.isEmpty) {
      throwIAE("interval string cannot be empty")
    }
    var state = PREFIX
    var i = 0
    var currentValue: Long = 0
    var isNegative: Boolean = false
    var months: Int = 0
    var days: Int = 0
    var microseconds: Long = 0
    var fractionScale: Int = 0
    val initialFractionScale = (NANOS_PER_SECOND / 10).toInt
    var fraction: Int = 0
    var pointPrefixed: Boolean = false

    def trimToNextState(b: Byte, next: ParseState): Unit = {
      b match {
        case ' ' => i += 1
        case _ => state = next
      }
    }

    def currentWord: UTF8String = {
      val strings = s.split(UTF8String.blankString(1), -1)
      val lenRight = s.substring(i, s.numBytes()).split(UTF8String.blankString(1), -1).length
      strings(strings.length - lenRight)
    }

    while (i < bytes.length) {
      val b = bytes(i)
      state match {
        case PREFIX =>
          if (s.startsWith(intervalStr)) {
            if (s.numBytes() == intervalStr.numBytes()) {
              throwIAE("interval string cannot be empty")
            } else {
              i += intervalStr.numBytes()
            }
          }
          state = TRIM_BEFORE_SIGN
        case TRIM_BEFORE_SIGN => trimToNextState(b, SIGN)
        case SIGN =>
          currentValue = 0
          fraction = 0
          // We preset next state from SIGN to TRIM_BEFORE_VALUE. If we meet '.' in the SIGN state,
          // it means that the interval value we deal with here is a numeric with only fractional
          // part, such as '.11 second', which can be parsed to 0.11 seconds. In this case, we need
          // to reset next state to `VALUE_FRACTIONAL_PART` to go parse the fraction part of the
          // interval value.
          state = TRIM_BEFORE_VALUE
          // We preset the scale to an invalid value to track fraction presence in the UNIT_BEGIN
          // state. If we meet '.', the scale become valid for the VALUE_FRACTIONAL_PART state.
          fractionScale = -1
          pointPrefixed = false
          b match {
            case '-' =>
              isNegative = true
              i += 1
            case '+' =>
              isNegative = false
              i += 1
            case _ if '0' <= b && b <= '9' =>
              isNegative = false
            case '.' =>
              isNegative = false
              fractionScale = initialFractionScale
              pointPrefixed = true
              i += 1
              state = VALUE_FRACTIONAL_PART
            case _ => throwIAE( s"unrecognized number '$currentWord'")
          }
        case TRIM_BEFORE_VALUE => trimToNextState(b, VALUE)
        case VALUE =>
          b match {
            case _ if '0' <= b && b <= '9' =>
              try {
                currentValue = Math.addExact(Math.multiplyExact(10, currentValue), (b - '0'))
              } catch {
                case e: ArithmeticException => throwIAE(e.getMessage, e)
              }
            case ' ' => state = TRIM_BEFORE_UNIT
            case '.' =>
              fractionScale = initialFractionScale
              state = VALUE_FRACTIONAL_PART
            case _ => throwIAE(s"invalid value '$currentWord'")
          }
          i += 1
        case VALUE_FRACTIONAL_PART =>
          b match {
            case _ if '0' <= b && b <= '9' && fractionScale > 0 =>
              fraction += (b - '0') * fractionScale
              fractionScale /= 10
            case ' ' if !pointPrefixed || fractionScale < initialFractionScale =>
              fraction /= NANOS_PER_MICROS.toInt
              state = TRIM_BEFORE_UNIT
            case _ if '0' <= b && b <= '9' =>
              throwIAE(s"interval can only support nanosecond precision, '$currentWord' is out" +
                s" of range")
            case _ => throwIAE(s"invalid value '$currentWord'")
          }
          i += 1
        case TRIM_BEFORE_UNIT => trimToNextState(b, UNIT_BEGIN)
        case UNIT_BEGIN =>
          // Checks that only seconds can have the fractional part
          if (b != 's' && fractionScale >= 0) {
            throwIAE(s"'$currentWord' cannot have fractional part")
          }
          if (isNegative) {
            currentValue = -currentValue
            fraction = -fraction
          }
          try {
            b match {
              case 'y' if s.matchAt(yearStr, i) =>
                val monthsInYears = Math.multiplyExact(MONTHS_PER_YEAR, currentValue)
                months = Math.toIntExact(Math.addExact(months, monthsInYears))
                i += yearStr.numBytes()
              case 'w' if s.matchAt(weekStr, i) =>
                val daysInWeeks = Math.multiplyExact(DAYS_PER_WEEK, currentValue)
                days = Math.toIntExact(Math.addExact(days, daysInWeeks))
                i += weekStr.numBytes()
              case 'd' if s.matchAt(dayStr, i) =>
                days = Math.addExact(days, Math.toIntExact(currentValue))
                i += dayStr.numBytes()
              case 'h' if s.matchAt(hourStr, i) =>
                val hoursUs = Math.multiplyExact(currentValue, MICROS_PER_HOUR)
                microseconds = Math.addExact(microseconds, hoursUs)
                i += hourStr.numBytes()
              case 's' if s.matchAt(secondStr, i) =>
                val secondsUs = Math.multiplyExact(currentValue, MICROS_PER_SECOND)
                microseconds = Math.addExact(Math.addExact(microseconds, secondsUs), fraction)
                i += secondStr.numBytes()
              case 'm' =>
                if (s.matchAt(monthStr, i)) {
                  months = Math.addExact(months, Math.toIntExact(currentValue))
                  i += monthStr.numBytes()
                } else if (s.matchAt(minuteStr, i)) {
                  val minutesUs = Math.multiplyExact(currentValue, MICROS_PER_MINUTE)
                  microseconds = Math.addExact(microseconds, minutesUs)
                  i += minuteStr.numBytes()
                } else if (s.matchAt(millisStr, i)) {
                  val millisUs = Math.multiplyExact(
                    currentValue,
                    MICROS_PER_MILLIS)
                  microseconds = Math.addExact(microseconds, millisUs)
                  i += millisStr.numBytes()
                } else if (s.matchAt(microsStr, i)) {
                  microseconds = Math.addExact(microseconds, currentValue)
                  i += microsStr.numBytes()
                } else throwIAE(s"invalid unit '$currentWord'")
              case _ => throwIAE(s"invalid unit '$currentWord'")
            }
          } catch {
            case e: ArithmeticException => throwIAE(e.getMessage, e)
          }
          state = UNIT_SUFFIX
        case UNIT_SUFFIX =>
          b match {
            case 's' => state = UNIT_END
            case ' ' => state = TRIM_BEFORE_SIGN
            case _ => throwIAE(s"invalid unit '$currentWord'")
          }
          i += 1
        case UNIT_END =>
          b match {
            case ' ' =>
              i += 1
              state = TRIM_BEFORE_SIGN
            case _ => throwIAE(s"invalid unit '$currentWord'")
          }
      }
    }

    val result = state match {
      case UNIT_SUFFIX | UNIT_END | TRIM_BEFORE_SIGN =>
        new CalendarInterval(months, days, microseconds)
      case _ => null
    }

    result
  }

  def makeInterval(
      years: Int,
      months: Int,
      weeks: Int,
      days: Int,
      hours: Int,
      mins: Int,
      secs: Decimal): CalendarInterval = {
    val totalMonths = Math.addExact(months, Math.multiplyExact(years, MONTHS_PER_YEAR))
    val totalDays = Math.addExact(days, Math.multiplyExact(weeks, DAYS_PER_WEEK))
    var micros = (secs * Decimal(MICROS_PER_SECOND)).toLong
    micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
    micros = Math.addExact(micros, Math.multiplyExact(mins, MICROS_PER_MINUTE))

    new CalendarInterval(totalMonths, totalDays, micros)
  }

  /**
   * Adjust interval so 30-day time periods are represented as months.
   */
  def justifyDays(interval: CalendarInterval): CalendarInterval = {
    val monthToDays = interval.months * DAYS_PER_MONTH
    val totalDays = monthToDays + interval.days
    val months = Math.toIntExact(totalDays / DAYS_PER_MONTH)
    val days = totalDays % DAYS_PER_MONTH
    new CalendarInterval(months, days.toInt, interval.microseconds)
  }

  /**
   * Adjust interval so 24-hour time periods are represented as days.
   */
  def justifyHours(interval: CalendarInterval): CalendarInterval = {
    val dayToUs = MICROS_PER_DAY * interval.days
    val totalUs = Math.addExact(interval.microseconds, dayToUs)
    val days = totalUs / MICROS_PER_DAY
    val microseconds = totalUs % MICROS_PER_DAY
    new CalendarInterval(interval.months, days.toInt, microseconds)
  }

  /**
   * Adjust interval using justifyHours and justifyDays, with additional sign adjustments.
   */
  def justifyInterval(interval: CalendarInterval): CalendarInterval = {
    val monthToDays = DAYS_PER_MONTH * interval.months
    val dayToUs = Math.multiplyExact(monthToDays + interval.days, MICROS_PER_DAY)
    val totalUs = Math.addExact(interval.microseconds, dayToUs)
    val microseconds = totalUs % MICROS_PER_DAY
    val totalDays = totalUs / MICROS_PER_DAY
    val days = totalDays % DAYS_PER_MONTH
    val months = totalDays / DAYS_PER_MONTH
    new CalendarInterval(months.toInt, days.toInt, microseconds)
  }
}
