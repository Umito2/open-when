import { randomUUID } from "node:crypto";
import type { File } from "@google-cloud/storage";

/** Return one stable token, even when simultaneous opens encounter an older upload without one. */
export async function ensureDownloadToken(file: File): Promise<string> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const [metadata] = await file.getMetadata();
    const existing = metadata.metadata?.firebaseStorageDownloadTokens;
    const firstToken = typeof existing === "string" ? existing.split(",")[0].trim() : "";
    if (firstToken) return firstToken;
    if (metadata.metageneration === undefined) throw new Error("Storage metadata generation is unavailable.");
    const token = randomUUID();
    try {
      await file.setMetadata(
        { metadata: { ...metadata.metadata, firebaseStorageDownloadTokens: token } },
        { ifMetagenerationMatch: metadata.metageneration },
      );
      return token;
    } catch (failure) {
      // Another opener won the compare-and-set. Read and reuse its token.
      if (Number((failure as { code?: unknown }).code) !== 412) throw failure;
    }
  }
  throw new Error("The attachment metadata is changing. Please try again.");
}
