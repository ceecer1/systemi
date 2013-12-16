package domain

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers


class InvoiceLinesAnalyzerTest extends FunSuite with ShouldMatchers with InvoiceLinesAnalyzer {

	test("should compute total properly") {
		val items = List(InvoiceLine("blabla", 25.0, 450.0))
		computeTotalHT(items) should be (25 * 450)
	}

	test("should compute total properly for multiple lines") {
		val items = List(InvoiceLine("blabla", 25.0, 450.0), InvoiceLine("blabla", 12.0, 350.0))
		computeTotalHT(items) should be (25 * 450 + 12*350)
	}

	test("should compute total properly for multiple lines with taxes") {
		val items = List(InvoiceLine("blabla", 25.0, 450.0), InvoiceLine("blabla", 12.0, 350.0))
		computeTotal(items) should be ((25.0 * 450.0 + 12.0 * 350) * (1.196))
	}

	test("should compute tva properly for multiple lines with rounding on two decimals") {
		val items = List(InvoiceLine("blabla", 25.0, 450.0), InvoiceLine("blabla", 12.0, 350.0))
		computeTva(items) should be (3028.20)
	}
}