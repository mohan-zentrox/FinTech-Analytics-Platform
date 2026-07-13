import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { login } from "../../api/auth";
import { useAuthStore } from "../../store/authStore";
import Login from "./Login";

vi.mock("../../api/auth", () => ({
  login: vi.fn(),
}));

const mockedLogin = vi.mocked(login);

describe("Login page", () => {
  beforeEach(() => {
    mockedLogin.mockReset();
    useAuthStore.setState({ token: null, username: null, role: null, isAuthenticated: false });
    window.localStorage.clear();
  });

  it("submits credentials and stores the returned session on success", async () => {
    mockedLogin.mockResolvedValueOnce({
      token: "signed.jwt.token",
      tokenType: "Bearer",
      username: "carol",
      role: "ANALYST",
      expiresInMs: 3600_000,
    });

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: "carol" } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(mockedLogin).toHaveBeenCalledWith("carol", "password123"));
    await waitFor(() => expect(useAuthStore.getState().isAuthenticated).toBe(true));
    expect(useAuthStore.getState().username).toBe("carol");
  });

  it("shows an error message when login fails", async () => {
    mockedLogin.mockRejectedValueOnce(new Error("Unauthorized"));

    render(
      <MemoryRouter>
        <Login />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: "carol" } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/invalid username or password/i);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
