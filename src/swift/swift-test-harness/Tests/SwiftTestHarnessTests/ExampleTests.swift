#if canImport(Testing)
import Testing
import JiffStatic

@Suite("JiffStatic Swift Export Tests")
struct JiffStaticExportTests {
    @Test("JiffStatic swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        #expect(Bool(true), "JiffStatic swift module imported cleanly")
    }
}
#elseif canImport(XCTest)
import XCTest
import JiffStatic

final class JiffStaticExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "JiffStatic swift module imported cleanly")
    }
}
#endif
