import { NextRequest, NextResponse } from "next/server";

const catalogOrigin = "https://folio-reader.jamaica-kidyneon.chatgpt.site";

export async function GET(request: NextRequest) {
  const target = new URL("/api/gutenberg", catalogOrigin);
  target.search = request.nextUrl.search;
  try {
    const response = await fetch(target, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    return new NextResponse(response.body, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
        "Cache-Control": "no-store",
      },
    });
  } catch {
    return NextResponse.json(
      { error: "The Gutenberg catalog could not be reached. Try again shortly." },
      { status: 502, headers: { "Cache-Control": "no-store" } },
    );
  }
}
