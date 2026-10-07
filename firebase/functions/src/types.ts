// Shared Firestore data model. Mirrors the Kotlin model in the Android app.

export interface Person {
  id: string; // e.g. "claire", "alastair", "alfie"
  name: string; // display name
  colour: string; // hex colour, e.g. "#D81B60"
  email?: string; // Google account used to sign in (lowercase)
}

export interface Family {
  name: string;
  timezone: string; // IANA, e.g. "Europe/London"
  reminderTime: string; // "HH:mm" local time for the morning push
  reminderDays: number[]; // ISO weekdays 1 (Mon) .. 7 (Sun)
  calendarId?: string; // shared Google Calendar ID ("" = sync off)
  notifyOnChange: boolean; // push when today's/tomorrow's plan is edited
  people: Person[];
  memberEmails: string[]; // lowercase emails allowed to join
  lastMorningSent?: string; // yyyy-MM-dd
}

export interface PersonDay {
  status: string; // "WFH", "Blackburn", "school"
  extras: string; // "2nd lunch DT clinic"
  travel: string; // "bus home"
}

export interface PlanItem {
  id: string;
  title: string;
  time?: string | null; // "HH:mm" — timed items sync to Google Calendar
  durationMins?: number;
  ownerPersonId?: string | null; // who is responsible / driving
  attendees?: string[]; // person ids involved
  notes?: string;
  recurringId?: string | null; // set when generated from a recurring item
  claimedBy?: string | null; // person id who tapped "I'll do it"
  done?: boolean;
}

export interface Day {
  date: string; // yyyy-MM-dd
  people: Record<string, PersonDay>;
  tea: string;
  items: PlanItem[];
  notes: string;
  skippedRecurring: string[]; // recurring ids removed from this day by a user
  updatedBy: string; // person id, or "system" for automatic writes
  userEdited: boolean; // true once a family member has saved this day
  updatedAt?: FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp;
}

export interface RecurringItem {
  title: string;
  time?: string | null;
  durationMins?: number;
  daysOfWeek: number[]; // ISO weekdays
  ownerPersonId?: string | null;
  attendees?: string[];
  notes?: string;
  active: boolean;
  startDate?: string | null; // optional yyyy-MM-dd bounds
  endDate?: string | null;
}

export interface WeekdayDefaults {
  people: Record<string, PersonDay>;
}

export const emptyPersonDay = (): PersonDay => ({ status: "", extras: "", travel: "" });
