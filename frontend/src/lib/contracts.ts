import { z } from "zod";

export const uuid = z.uuid();
export const statusSchema = z.enum(["ACTIVE", "FULL_OCCUPIED", "PAUSED"]);
export const segmentSchema = z.enum([
  "ROOM",
  "HALL",
  "BATHROOM",
  "KITCHEN",
  "EXTERIOR_AMENITY",
]);
export const badgeSchema = z.enum([
  "STANDARD",
  "TOP_RATED",
  "LOW_RATINGS_BEWARE",
  "BROKERAGE_WARNED",
  "CRITICAL_BROKER_AND_LOW_RATING",
]);
export const ownerSchema = z.object({
  fullName: z.string(),
  averageRating: z.number().nullable(),
  reviewCount: z.number().int(),
  brokerReportCount: z.number().int(),
  trustBadge: badgeSchema,
});
export const imageSchema = z.object({
  imageId: uuid,
  segment: segmentSchema,
  displayOrder: z.number().int(),
  contentType: z.string().nullable(),
  byteSize: z.number().nullable(),
  width: z.number().nullable(),
  height: z.number().nullable(),
  url: z.string().nullable(),
  expiresAt: z.string().nullable(),
});
const commonListing = z.object({
  id: uuid,
  ownerId: uuid,
  title: z.string(),
  description: z.string(),
  status: statusSchema,
  city: z.string(),
  locality: z.string(),
  monthlyRent: z.number().int().positive(),
  securityDeposit: z.number().int().nonnegative(),
  currency: z.literal("INR"),
  rentPeriod: z.literal("MONTH"),
  hasWifi: z.boolean(),
  hasGym: z.boolean(),
  hasSwimmingPool: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string(),
  owner: ownerSchema,
  images: z.array(imageSchema),
  publicLocation: z.object({
    latitude: z.number(),
    longitude: z.number(),
    radiusMeters: z.literal(2000),
  }),
});
const entire = commonListing.extend({
  category: z.literal("FLAT"),
  flatRentalMode: z.literal("ENTIRE_FLAT"),
  rentBasis: z.literal("PER_PROPERTY"),
  bedrooms: z.number().int(),
  wholeFlatAvailable: z.boolean(),
  maxOccupants: z.number().int().positive(),
  maleRoomsAvailable: z.null(),
  femaleRoomsAvailable: z.null(),
  sharedRoomsAvailable: z.null(),
  totalRoomsAvailable: z.null(),
  maxSharingPerRoom: z.null(),
});
const rooms = commonListing.extend({
  category: z.literal("FLAT"),
  flatRentalMode: z.literal("ROOM"),
  rentBasis: z.literal("PER_ROOM"),
  bedrooms: z.number().int(),
  wholeFlatAvailable: z.null(),
  maxOccupants: z.null(),
  maleRoomsAvailable: z.number().int(),
  femaleRoomsAvailable: z.number().int(),
  sharedRoomsAvailable: z.number().int(),
  totalRoomsAvailable: z.number().int(),
  maxSharingPerRoom: z.number().int().min(1).max(10),
});
const pg = rooms.extend({
  category: z.literal("PG_HOSTEL"),
  flatRentalMode: z.null(),
  rentBasis: z.literal("PER_PERSON"),
  bedrooms: z.null(),
});
export const listingSchema = z.union([entire, rooms, pg]);
const privateFields = {
  version: z.number().int().nonnegative(),
  privateAddress: z.string(),
  latitude: z.number(),
  longitude: z.number(),
};
export const ownerListingSchema = z.union([
  entire.extend(privateFields),
  rooms.extend(privateFields),
  pg.extend(privateFields),
]);
export type Listing = z.infer<typeof listingSchema>;
export type OwnerListing = z.infer<typeof ownerListingSchema>;
export type ImageSegment = z.infer<typeof segmentSchema>;
export type Badge = z.infer<typeof badgeSchema>;
export const accountSchema = ownerSchema
  .omit({ fullName: true })
  .extend({
    id: uuid,
    fullName: z.string(),
    role: z.literal("USER"),
    phoneNumber: z.string().nullable(),
    phoneVerified: z.boolean(),
  });
export type Account = z.infer<typeof accountSchema>;
export const reviewSchema = z.object({
  id: uuid,
  rating: z.number().int().min(1).max(5),
  issueTag: z.enum([
    "GENUINE_OWNER",
    "ASKED_BROKERAGE",
    "MAINTENANCE_ISSUE",
    "FAKE_PHOTOS",
    "OTHER",
  ]),
  commentText: z.string().nullable(),
  isAutoBrokerReport: z.boolean(),
  createdAt: z.string(),
  allegationNotice: z.string().nullable(),
});
export const stateSchema = z.object({
  hasHistoricalReveal: z.boolean(),
  revealedToday: z.boolean(),
  quotaDate: z.string(),
  remainingReveals: z.number().int(),
  contactActionsEnabled: z.boolean(),
  canReview: z.boolean(),
  canReportBrokerage: z.boolean(),
  review: reviewSchema.nullable(),
});
export const revealSchema = z.object({
  phoneNumber: z.string().regex(/^\+91[0-9]{10}$/),
  whatsappUrl: z.string().regex(/^https:\/\/wa\.me\/91[0-9]{10}$/),
  revealedDate: z.string(),
  remainingReveals: z.number().int(),
});
export const challengeSchema = z.object({
  challengeId: uuid,
  botUrl: z
    .string()
    .url()
    .refine(
      (value) =>
        new URL(value).protocol === "https:" &&
        new URL(value).hostname === "t.me",
    ),
  expiresAt: z.iso.datetime({ offset: true }),
  restartAt: z.iso.datetime({ offset: true }),
  status: z.string(),
});
export const challengeStatusSchema = z.object({
  status: z.enum([
    "WAITING_BOT",
    "WAITING_CONTACT",
    "CODE_READY",
    "CODE_SENT",
    "COMPLETING",
    "VERIFIED",
    "CANCELLED",
    "LOCKED",
    "COMPLETED",
  ]),
  expiresAt: z.iso.datetime({ offset: true }).optional(),
  resendAt: z.iso.datetime({ offset: true }).optional(),
  resendsRemaining: z.number().int().min(0).max(3).optional(),
});
export const authenticatedSchema = z.object({
  status: z.literal("AUTHENTICATED"),
});
export const loginCompletionSchema = z.discriminatedUnion("status", [
  authenticatedSchema,
  z.object({ status: z.literal("NAME_REQUIRED") }),
]);
export const csrfSchema = z.object({
  token: z.string(),
  headerName: z.literal("X-CSRF-TOKEN"),
});
export const personasSchema = z.object({
  personas: z.array(z.object({ key: z.string(), displayName: z.string() })),
});
export const presignSchema = z.object({
  imageId: uuid,
  objectKey: z.string(),
  uploadUrl: z.string().url(),
  expiresAt: z.string(),
  requiredHeaders: z.record(z.string(), z.string()),
});

export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    items: z.array(item),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
    hasNext: z.boolean(),
    hasPrevious: z.boolean(),
    sort: z.string(),
  });
}
export type ListingPage = z.infer<
  ReturnType<typeof pageSchema<typeof listingSchema>>
>;
